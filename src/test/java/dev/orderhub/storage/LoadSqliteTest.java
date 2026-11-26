package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduzierbarer Lasttest auf SQLite: 100.000 Auftraege, danach parallele Suchen mit Paginierung
 * und Sortierung. Gemessene Werte stehen in der Testausgabe und in {@code build/reports/load.txt}.
 * Laeuft nur mit {@code ./gradlew loadTest}.
 */
@Tag("load")
class LoadSqliteTest {

  private static final int ORDERS = 100_000;
  private static final int BATCH = 1_000;

  @TempDir Path dir;

  @Test
  void hundredThousandOrdersWithParallelSearches() throws Exception {
    try (JdbcStorage storage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("load.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("load"))) {
      String[] items = new String[200];
      for (int i = 0; i < items.length; i++) {
        items[i] = "minecraft:item_" + i;
      }
      long insertStart = System.nanoTime();
      for (int b = 0; b < ORDERS / BATCH; b++) {
        final int base = b * BATCH;
        storage
            .transact(uow -> {
              for (int i = 0; i < BATCH; i++) {
                int n = base + i;
                uow.orders()
                    .insert(
                        new NewOrder(
                            UUID.randomUUID(),
                            "load",
                            TestData.item(items[n % items.length]),
                            100 + (n % 5000),
                            1 + (n % 64),
                            1,
                            Long.MAX_VALUE / 2,
                            false,
                            "items"),
                        TestData.T0 + n);
              }
              return null;
            })
            .join();
      }
      long insertMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStart);
      long total =
          storage
              .transact(uow ->
                  uow.orders()
                      .count(new OrderQuery(null, null, null, OrderStatus.ACTIVE, null, 0, 1)))
              .join();
      assertThat(total).isEqualTo(ORDERS);

      OrderQuery.Sort[] sorts = OrderQuery.Sort.values();
      int threads = 8;
      int perThread = 60;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<List<Long>>> futures = new ArrayList<>();
      long queryStart = System.nanoTime();
      for (int t = 0; t < threads; t++) {
        final int seed = t;
        futures.add(
            pool.submit(() -> {
              List<Long> times = new ArrayList<>();
              for (int q = 0; q < perThread; q++) {
                OrderQuery.Sort sort = sorts[(seed + q) % sorts.length];
                String item = (q % 3 == 0) ? items[(seed * 7 + q) % items.length] : null;
                int offset = (q % 10) * 45;
                long s0 = System.nanoTime();
                List<Order> page =
                    storage
                        .transact(uow ->
                            uow.orders()
                                .search(
                                    new OrderQuery(
                                        null, item, null, OrderStatus.ACTIVE, sort, offset, 45)))
                        .join();
                times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s0));
                assertThat(page.size()).isLessThanOrEqualTo(45);
              }
              return times;
            }));
      }
      List<Long> all = new ArrayList<>();
      for (Future<List<Long>> f : futures) {
        all.addAll(f.get(10, TimeUnit.MINUTES));
      }
      pool.shutdown();
      long queryWallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStart);
      Collections.sort(all);
      long p50 = all.get(all.size() / 2);
      long p95 = all.get((int) (all.size() * 0.95));
      long max = all.get(all.size() - 1);
      String report =
          "SQLite load test, "
              + ORDERS
              + " orders\n"
              + "insert total: "
              + insertMs
              + " ms ("
              + (ORDERS * 1000L / Math.max(1, insertMs))
              + " orders/s)\n"
              + "queries: "
              + all.size()
              + " in "
              + queryWallMs
              + " ms wall time, "
              + threads
              + " threads\n"
              + "latency ms: p50="
              + p50
              + " p95="
              + p95
              + " max="
              + max
              + "\n";
      System.out.println(report);
      Path out = Path.of("build/reports/load.txt");
      Files.createDirectories(out.getParent());
      Files.writeString(out, report.replace("\n", System.lineSeparator()));
    }
  }
}
