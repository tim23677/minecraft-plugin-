package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Lasttest auf MariaDB 11.4 (Testcontainers): 100.000 Auftraege, danach parallele Suchen mit
 * Offset- und Keyset-Paginierung, Textsuche und Kategoriefilter von zwei Servern gleichzeitig.
 * Ergebnis in der Testausgabe und in {@code build/reports/load-mariadb.txt}. Laeuft nur mit {@code
 * ./gradlew loadTest} und einem Docker-Daemon.
 */
@Tag("load")
class LoadMariaDbTest {

  private static final int ORDERS = 100_000;
  private static final int BATCH = 1_000;

  @Test
  void hundredThousandOrdersWithParallelSearchesFromTwoServers() throws Exception {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker fuer Testcontainers fehlt");
    try (MariaDBContainer container = new MariaDBContainer("mariadb:11.4")) {
      container.start();
      HikariDataSource ds1 =
          DataSourceFactory.mariaDb(
              container.getJdbcUrl(), container.getUsername(), container.getPassword(), 8);
      Migrations.apply(ds1, Dialect.MARIADB);
      HikariDataSource ds2 =
          DataSourceFactory.mariaDb(
              container.getJdbcUrl(), container.getUsername(), container.getPassword(), 8);
      try (JdbcStorage server1 =
              new JdbcStorage(ds1, Dialect.MARIADB, StorageConfig.defaults("load-a"));
          JdbcStorage server2 =
              new JdbcStorage(ds2, Dialect.MARIADB, StorageConfig.defaults("load-b"))) {
        run(server1, server2);
      }
    }
  }

  private static void run(JdbcStorage server1, JdbcStorage server2) throws Exception {
    String[] items = new String[200];
    String[] categories = {"ores", "blocks", "farm", "mob-drops", "tools", "potions", "misc"};
    for (int i = 0; i < items.length; i++) {
      items[i] = "minecraft:item_" + i;
    }
    long insertStart = System.nanoTime();
    for (int b = 0; b < ORDERS / BATCH; b++) {
      final int base = b * BATCH;
      server1
          .transact(uow -> {
            for (int i = 0; i < BATCH; i++) {
              int n = base + i;
              uow.orders()
                  .insert(
                      new NewOrder(
                          UUID.randomUUID(),
                          "load-a",
                          TestData.item(items[n % items.length]),
                          100 + (n % 5000),
                          1 + (n % 64),
                          1,
                          Long.MAX_VALUE / 2,
                          false,
                          categories[n % categories.length]),
                      TestData.T0 + n);
            }
            return null;
          })
          .join();
    }
    long insertMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStart);
    long total =
        server2
            .transact(uow ->
                uow.orders()
                    .count(new OrderQuery(null, null, null, OrderStatus.ACTIVE, null, 0, 1)))
            .join();
    assertThat(total).isEqualTo(ORDERS);

    OrderQuery.Sort[] sorts = OrderQuery.Sort.values();
    int threads = 8;
    int perThread = 60;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<List<long[]>>> futures = new ArrayList<>();
    long queryStart = System.nanoTime();
    for (int t = 0; t < threads; t++) {
      final int seed = t;
      final JdbcStorage storage = t % 2 == 0 ? server1 : server2;
      futures.add(
          pool.submit(() -> {
            List<long[]> times = new ArrayList<>(); // {art, millis}
            for (int q = 0; q < perThread; q++) {
              OrderQuery.Sort sort = sorts[(seed + q) % sorts.length];
              int kind = q % 4; // 0 Offset, 1 Keyset tief, 2 Text, 3 Kategorie
              String text = kind == 2 ? "item_" + ((seed * 7 + q) % items.length) : null;
              String category = kind == 3 ? categories[(seed + q) % categories.length] : null;
              long s0 = System.nanoTime();
              List<Order> page;
              if (kind == 1) {
                // Tiefe Seite per Keyset: erst 20 Seiten vorblaettern, dann messen
                OrderQuery.Cursor cursor = null;
                page = List.of();
                for (int p = 0; p < 20; p++) {
                  OrderQuery.Cursor after = cursor;
                  page =
                      storage
                          .transact(uow ->
                              uow.orders()
                                  .search(
                                      new OrderQuery(
                                          null,
                                          null,
                                          null,
                                          OrderStatus.ACTIVE,
                                          sort,
                                          0,
                                          45,
                                          null,
                                          after)))
                          .join();
                  if (page.isEmpty()) {
                    break;
                  }
                  cursor = OrderQuery.Cursor.of(sort, page.get(page.size() - 1));
                }
                times.add(
                    new long[] {1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s0) / 20});
              } else {
                int offset = kind == 0 ? (q % 10) * 45 : 0;
                page =
                    storage
                        .transact(uow ->
                            uow.orders()
                                .search(
                                    new OrderQuery(
                                        null,
                                        null,
                                        category,
                                        OrderStatus.ACTIVE,
                                        sort,
                                        offset,
                                        45,
                                        text,
                                        null)))
                        .join();
                times.add(new long[] {kind, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s0)});
              }
              assertThat(page.size()).isLessThanOrEqualTo(45);
            }
            return times;
          }));
    }
    List<long[]> all = new ArrayList<>();
    for (Future<List<long[]>> f : futures) {
      all.addAll(f.get(15, TimeUnit.MINUTES));
    }
    pool.shutdown();
    long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStart);
    StringBuilder report = new StringBuilder();
    report
        .append("MariaDB 11.4 load test, ")
        .append(ORDERS)
        .append(" orders, two servers\n")
        .append("insert total: ")
        .append(insertMs)
        .append(" ms (")
        .append(ORDERS * 1000L / Math.max(1, insertMs))
        .append(" orders/s)\n")
        .append("queries: ")
        .append(all.size())
        .append(" in ")
        .append(wallMs)
        .append(" ms wall time, ")
        .append(threads)
        .append(" threads over 2 servers\n");
    String[] names = {
      "offset page", "keyset page (avg of 20 deep pages)", "text search", "category filter"
    };
    for (int kind = 0; kind < names.length; kind++) {
      final int k = kind;
      List<Long> v = new ArrayList<>(all.stream().filter(x -> x[0] == k).map(x -> x[1]).toList());
      Collections.sort(v);
      report.append(
          String.format(
              "%-36s n=%d p50=%d p95=%d max=%d ms%n",
              names[kind],
              v.size(),
              v.get(v.size() / 2),
              v.get((int) (v.size() * 0.95)),
              v.get(v.size() - 1)));
    }
    System.out.println(report);
    Path out = Path.of("build/reports/load-mariadb.txt");
    Files.createDirectories(java.util.Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString().replace("\n", System.lineSeparator()));
  }
}
