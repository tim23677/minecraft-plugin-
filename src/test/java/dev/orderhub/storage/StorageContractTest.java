package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.tx.InvalidTransitionException;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Vertrag der Storage-Schicht, identisch gegen SQLite und MariaDB. */
abstract class StorageContractTest {

  static final int THREADS = 8;
  static final long TIMEOUT_SECONDS = 60;

  private final List<JdbcStorage> opened = new ArrayList<>();

  /** Oeffnet eine frische, migrierte Instanz auf derselben Datenbank (wie ein weiterer Server). */
  abstract JdbcStorage open(String serverId, StorageConfig config) throws Exception;

  /** Roher Zugriff, nur fuer Schema- und Trigger-Pruefungen. */
  abstract DataSource rawDataSource();

  /** Tabellen leeren bzw. Datenbank neu aufbauen. */
  abstract void resetDatabase() throws Exception;

  abstract Dialect dialect();

  private JdbcStorage primary;

  JdbcStorage storage() {
    if (primary == null) {
      primary = server("srv-a");
    }
    return primary;
  }

  JdbcStorage server(String id) {
    try {
      JdbcStorage s = open(id, StorageConfig.defaults(id).withBackoff(5, 50));
      opened.add(s);
      return s;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterEach
  void closeAll() throws Exception {
    for (JdbcStorage s : opened) {
      s.close();
    }
    opened.clear();
    primary = null;
  }

  static <T> T tx(Storage s, Function<UnitOfWork, T> work) {
    try {
      return s.transact(work).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean txBool(Storage s, Function<UnitOfWork, Boolean> work) {
    return Boolean.TRUE.equals(tx(s, work));
  }

  // ---------------------------------------------------------------- Migrationen

  @Test
  void migrationCreatesAllTablesAndIsIdempotent() throws Exception {
    storage();
    Set<String> expected =
        Set.of(
            "orders",
            "order_items",
            "order_deliveries",
            "escrow_accounts",
            "transactions",
            "transaction_journal",
            "payout_queue",
            "refund_queue",
            "item_claims",
            "item_returns",
            "delivery_holds",
            "player_statistics",
            "player_favorites",
            "price_history",
            "market_snapshots",
            "notifications",
            "discord_outbox",
            "order_options",
            "order_templates",
            "cache_outbox",
            "server_registry",
            "schema_version",
            "audit_log");
    DataSource ds = rawDataSource();
    try (Connection c = ds.getConnection();
        var rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
      Set<String> found = new HashSet<>();
      while (rs.next()) {
        found.add(rs.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
      }
      assertThat(found).containsAll(expected);
    }
    assertThat(Migrations.apply(ds, dialect())).isZero();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        var rs = st.executeQuery("SELECT COUNT(*), MIN(LENGTH(checksum)) FROM schema_version")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(7);
      assertThat(rs.getInt(2)).isEqualTo(64);
    }
  }

  /** Synthetische Migrationen fuer Reihenfolge- und Fehlertests. */
  private static final class FakeSource implements Migrations.Source {
    private final java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
    private final java.util.List<Migrations.Script> scripts = new ArrayList<>();
    final java.util.List<String> readOrder = new ArrayList<>();

    FakeSource add(int version, String name, String sql) {
      String path = "common/V" + version + "__" + name + ".sql";
      files.put(path, sql);
      scripts.add(new Migrations.Script(version, "common", name, path));
      return this;
    }

    @Override
    public java.util.List<Migrations.Script> scripts(Dialect dialect) {
      return new ArrayList<>(scripts);
    }

    @Override
    public String read(String path) {
      readOrder.add(path);
      return files.get(path);
    }
  }

  private java.util.List<Integer> appliedVersions(DataSource ds) throws Exception {
    java.util.List<Integer> versions = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        var rs =
            st.executeQuery("SELECT version FROM schema_version ORDER BY applied_at, version")) {
      while (rs.next()) {
        versions.add(rs.getInt(1));
      }
    }
    return versions;
  }

  @Test
  void stepsRunInVersionOrderAndEachExactlyOnce() throws Exception {
    DataSource ds = rawDataSource();
    // Absichtlich in falscher Reihenfolge angegeben.
    FakeSource source =
        new FakeSource()
            .add(3, "index", "CREATE INDEX ix_t2_owner ON t2 (owner)")
            .add(
                1,
                "first",
                "CREATE TABLE t1 (id ${AUTO_ID}, owner CHAR(36) NOT NULL) ${TABLE_OPTS}")
            .add(
                2,
                "second",
                "CREATE TABLE t2 (id ${AUTO_ID}, owner CHAR(36) NOT NULL) ${TABLE_OPTS}");
    assertThat(Migrations.apply(ds, dialect(), source)).isEqualTo(3);
    assertThat(source.readOrder)
        .containsExactly("common/V1__first.sql", "common/V2__second.sql", "common/V3__index.sql");
    assertThat(appliedVersions(ds)).containsExactly(1, 2, 3);
    // Zweiter Lauf: nichts mehr anzuwenden, nichts doppelt.
    assertThat(Migrations.apply(ds, dialect(), source)).isZero();
    assertThat(appliedVersions(ds)).containsExactly(1, 2, 3);
  }

  @Test
  void aFailedStepKeepsEarlierStepsAndTheRunCanBeRepeated() throws Exception {
    DataSource ds = rawDataSource();
    FakeSource broken =
        new FakeSource()
            .add(1, "first", "CREATE TABLE t1 (id ${AUTO_ID}) ${TABLE_OPTS}")
            .add(2, "bad", "THIS IS NOT SQL")
            .add(3, "never", "CREATE TABLE t3 (id ${AUTO_ID}) ${TABLE_OPTS}");
    assertThatThrownBy(() -> Migrations.apply(ds, dialect(), broken))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("V2__bad.sql");
    assertThat(appliedVersions(ds)).containsExactly(1);
    FakeSource fixed =
        new FakeSource()
            .add(1, "first", "CREATE TABLE t1 (id ${AUTO_ID}) ${TABLE_OPTS}")
            .add(2, "bad", "CREATE TABLE t2 (id ${AUTO_ID}) ${TABLE_OPTS}")
            .add(3, "never", "CREATE TABLE t3 (id ${AUTO_ID}) ${TABLE_OPTS}");
    // Der geaenderte Schritt 2 war nie angewendet, deshalb kein Hash-Konflikt.
    assertThat(Migrations.apply(ds, dialect(), fixed)).isEqualTo(2);
    assertThat(appliedVersions(ds)).containsExactly(1, 2, 3);
  }

  @Test
  void aFailedStepLeavesNothingBehindOnSqlite() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        dialect() == Dialect.SQLITE, "MariaDB-DDL committet implizit und ist nicht rueckrollbar");
    DataSource ds = rawDataSource();
    FakeSource broken =
        new FakeSource()
            .add(
                1,
                "half",
                "CREATE TABLE t_half (id ${AUTO_ID}) ${TABLE_OPTS}\n-- @@\nTHIS IS NOT SQL");
    assertThatThrownBy(() -> Migrations.apply(ds, dialect(), broken))
        .isInstanceOf(MigrationException.class);
    try (Connection c = ds.getConnection();
        var rs =
            c.getMetaData().getTables(c.getCatalog(), null, "t_half", new String[] {"TABLE"})) {
      assertThat(rs.next()).isFalse();
    }
    assertThat(appliedVersions(ds)).isEmpty();
  }

  @Test
  void ordersGetGeneratedIdsAndAnOwnerIndex() throws Exception {
    storage();
    DataSource ds = rawDataSource();
    try (Connection c = ds.getConnection();
        var rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, "orders", false, false)) {
      boolean ownerIndex = false;
      while (rs.next()) {
        if ("owner_uuid".equalsIgnoreCase(rs.getString("COLUMN_NAME"))
            && rs.getShort("ORDINAL_POSITION") == 1) {
          ownerIndex = true;
        }
      }
      assertThat(ownerIndex).isTrue();
    }
    java.util.UUID owner = java.util.UUID.randomUUID();
    var first =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(owner, "minecraft:dirt", 1, 1), u.now()));
    var second =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(owner, "minecraft:dirt", 1, 1), u.now()));
    assertThat(second.id()).isGreaterThan(first.id());
  }

  @Test
  void modifiedAppliedMigrationAbortsStart() throws Exception {
    storage();
    DataSource ds = rawDataSource();
    try (Connection c = ds.getConnection();
        var ps =
            c.prepareStatement("UPDATE schema_version SET checksum = ? WHERE scope = 'common'")) {
      ps.setString(1, "0".repeat(64));
      ps.executeUpdate();
    }
    assertThatThrownBy(() -> Migrations.apply(ds, dialect()))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("modified after it was applied");
  }

  @Test
  void parallelMigrationFromSeveralServersAppliesEachFileOnce() throws Exception {
    resetDatabase();
    DataSource ds = rawDataSource();
    Dialect dialect = dialect();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        results.add(pool.submit(() -> Migrations.apply(ds, dialect)));
      }
      int total = 0;
      for (Future<Integer> f : results) {
        total += f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      assertThat(total).isEqualTo(7);
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- Orders

  @Test
  void orderInsertFindAndEscrowAccount() throws Exception {
    UUID owner = UUID.randomUUID();
    Order created =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders()
                    .insert(TestData.order(owner, "minecraft:diamond", 500, 10), TestData.T0));
    assertThat(created.id()).isPositive();
    assertThat(created.version()).isZero();
    assertThat(created.escrowRemaining()).isEqualTo(5000);
    assertThat(created.escrowConsistent()).isTrue();
    Order loaded = tx(storage(), (UnitOfWork u) -> u.orders().find(created.id())).orElseThrow();
    assertThat(loaded).isEqualTo(created);
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().find(created.id() + 999))).isEmpty();
    assertThat(escrowOf(owner)).isEqualTo(5000);
  }

  @Test
  void orderUpdateIsCompareAndSetOnVersion() {
    UUID owner = UUID.randomUUID();
    Order o =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders()
                    .insert(TestData.order(owner, "minecraft:iron_ingot", 100, 10), TestData.T0));
    Order delivered = o.afterDelivery(4, TestData.T0 + 1);
    assertThat(txBool(storage(), (UnitOfWork u) -> u.orders().update(delivered))).isTrue();
    // Zweiter Schreiber mit derselben Ausgangsversion verliert.
    Order conflicting = o.afterDelivery(2, TestData.T0 + 2);
    assertThat(txBool(storage(), (UnitOfWork u) -> u.orders().update(conflicting))).isFalse();
    Order stored = tx(storage(), (UnitOfWork u) -> u.orders().find(o.id())).orElseThrow();
    assertThat(stored).isEqualTo(delivered);
    assertThat(stored.quantityFilled()).isEqualTo(4);
    assertThat(stored.version()).isEqualTo(1);
    assertThat(escrowOf(owner)).isEqualTo(600);
    // Unbekannte Id
    Order ghost = stored.withVersionAndStatus(2, OrderStatus.PAUSED, TestData.T0);
    Order ghostOrder =
        new Order(
            stored.id() + 99,
            ghost.owner(),
            ghost.serverId(),
            ghost.item(),
            ghost.unitPrice(),
            ghost.quantityTotal(),
            ghost.quantityFilled(),
            ghost.minDelivery(),
            ghost.escrowRemaining(),
            ghost.status(),
            ghost.createdAt(),
            ghost.expiresAt(),
            ghost.updatedAt(),
            1,
            ghost.autoRenew(),
            ghost.category());
    assertThat(txBool(storage(), (UnitOfWork u) -> u.orders().update(ghostOrder))).isFalse();
  }

  @Test
  void completedOrderReleasesEscrowAndStatusPersists() {
    UUID owner = UUID.randomUUID();
    Order o =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(owner, "minecraft:coal", 10, 5), TestData.T0));
    Order done = o.afterDelivery(5, TestData.T0 + 5);
    assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(txBool(storage(), (UnitOfWork u) -> u.orders().update(done))).isTrue();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().find(o.id())).orElseThrow().status())
        .isEqualTo(OrderStatus.COMPLETED);
    assertThat(escrowOf(owner)).isZero();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().countOpenByOwner(owner))).isZero();
  }

  @Test
  void checkConstraintRejectsOverfill() {
    UUID owner = UUID.randomUUID();
    Order o =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(owner, "minecraft:coal", 10, 5), TestData.T0));
    Order bad =
        new Order(
            o.id(),
            owner,
            o.serverId(),
            o.item(),
            10,
            5,
            5,
            1,
            50,
            OrderStatus.ACTIVE,
            o.createdAt(),
            o.expiresAt(),
            1,
            1,
            false,
            o.category());
    // quantity_filled = total ist erlaubt, escrow_remaining negativ nicht (Record verhindert es),
    // also Fuellstand > total:
    assertThatThrownBy(() ->
            new Order(
                o.id(),
                owner,
                o.serverId(),
                o.item(),
                10,
                5,
                6,
                1,
                0,
                OrderStatus.ACTIVE,
                1,
                1,
                1,
                1,
                false,
                "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(txBool(storage(), (UnitOfWork u) -> u.orders().update(bad))).isTrue();
  }

  @Test
  void searchFiltersSortsAndPaginates() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders()
              .insert(
                  new NewOrder(
                      a,
                      "srv-a",
                      TestData.item("minecraft:diamond"),
                      900,
                      10,
                      1,
                      TestData.T0 + 50,
                      false,
                      "gems"),
                  TestData.T0 + 1);
          u.orders()
              .insert(
                  new NewOrder(
                      a,
                      "srv-a",
                      TestData.item("minecraft:iron_ingot"),
                      100,
                      50,
                      1,
                      TestData.T0 + 500,
                      false,
                      "ores"),
                  TestData.T0 + 2);
          u.orders()
              .insert(
                  new NewOrder(
                      b,
                      "srv-a",
                      TestData.item("minecraft:diamond"),
                      700,
                      4,
                      1,
                      TestData.T0 + 900,
                      false,
                      "gems"),
                  TestData.T0 + 3);
          return null;
        });
    var all = tx(storage(), (UnitOfWork u) -> u.orders().search(OrderQuery.firstPage()));
    assertThat(all)
        .extracting(Order::createdAt)
        .containsExactly(TestData.T0 + 3, TestData.T0 + 2, TestData.T0 + 1);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, OrderQuery.Sort.HIGHEST_PRICE, 0, 10))))
        .extracting(Order::unitPrice)
        .containsExactly(900L, 700L, 100L);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, OrderQuery.Sort.LOWEST_PRICE, 0, 10))))
        .extracting(Order::unitPrice)
        .containsExactly(100L, 700L, 900L);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, OrderQuery.Sort.HIGHEST_DEMAND, 0, 10))))
        .extracting(Order::quantityTotal)
        .containsExactly(50, 10, 4);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, OrderQuery.Sort.ALMOST_COMPLETED, 0, 10))))
        .hasSize(3);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().search(new OrderQuery(a, null, null, null, null, 0, 10))))
        .hasSize(2);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(null, "minecraft:diamond", "gems", null, null, 0, 10))))
        .hasSize(2);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .count(new OrderQuery(null, "minecraft:diamond", null, null, null, 0, 10))))
        .isEqualTo(2);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().search(new OrderQuery(null, null, null, null, null, 1, 1))))
        .hasSize(1)
        .first()
        .extracting(Order::createdAt)
        .isEqualTo(TestData.T0 + 2);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(null, null, null, OrderStatus.CANCELLED, null, 0, 10))))
        .isEmpty();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().countOpenByOwner(a))).isEqualTo(2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().findExpired(TestData.T0 + 600, 10)))
        .extracting(Order::category)
        .containsExactly("gems", "ores");
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().findExpired(TestData.T0 + 10, 10)))
        .isEmpty();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().findForUpdate(all.get(0).id())))
        .isPresent();
  }

  @Test
  void sqlInjectionInFiltersAndValuesIsHarmless() throws Exception {
    UUID owner = UUID.randomUUID();
    String evilKey = "minecraft:x' OR '1'='1";
    String evilCategory = "x'; DROP TABLE orders; --";
    tx(
        storage(),
        (UnitOfWork u) ->
            u.orders().insert(TestData.order(owner, "minecraft:diamond", 100, 3), TestData.T0));
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().search(new OrderQuery(null, evilKey, null, null, null, 0, 10))))
        .isEmpty();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().search(new OrderQuery(null, null, evilCategory, null, null, 0, 10))))
        .isEmpty();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .count(
                            new OrderQuery(null, "' OR 1=1 --", evilCategory, null, null, 0, 10))))
        .isZero();
    // Als gespeicherter Wert kommt der Text unveraendert wieder heraus, ohne etwas auszufuehren.
    NewOrder evil =
        new NewOrder(
            owner,
            "srv-a",
            TestData.item(evilKey),
            100,
            1,
            1,
            TestData.T0 + 5,
            false,
            evilCategory);
    Order stored = tx(storage(), (UnitOfWork u) -> u.orders().insert(evil, TestData.T0));
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.orders().find(stored.id())).orElseThrow().category())
        .isEqualTo(evilCategory);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(new OrderQuery(null, evilKey, evilCategory, null, null, 0, 10))))
        .hasSize(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().count(OrderQuery.firstPage())))
        .isEqualTo(2);
    // Idempotency-Key mit SQL-Text
    JournalEntry e = TestData.journal("k'; DELETE FROM orders; --", owner, null);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().insertIfAbsent(e)).created()).isTrue();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().count(OrderQuery.firstPage())))
        .isEqualTo(2);
  }

  @Test
  void concurrentDeliveriesAcrossTwoServersLoseNoUpdate() throws Exception {
    UUID owner = UUID.randomUUID();
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    int total = 40;
    Order o =
        tx(
            s1,
            (UnitOfWork u) ->
                u.orders()
                    .insert(TestData.order(owner, "minecraft:stone", 10, total), TestData.T0));
    AtomicInteger wins = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          while (true) {
            int outcome =
                tx(
                    s,
                    (UnitOfWork u) -> {
                      Order cur = u.orders().findForUpdate(o.id()).orElseThrow();
                      if (cur.remaining() == 0) {
                        return -1;
                      }
                      return u.orders().update(cur.afterDelivery(1, u.now())) ? 1 : 0;
                    });
            if (outcome < 0) {
              return;
            }
            (outcome == 1 ? wins : conflicts).incrementAndGet();
          }
        });
    Order end = tx(s1, (UnitOfWork u) -> u.orders().find(o.id())).orElseThrow();
    assertThat(wins.get()).isEqualTo(total);
    assertThat(end.quantityFilled()).isEqualTo(total);
    assertThat(end.version()).isEqualTo(total);
    assertThat(end.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(end.escrowRemaining()).isZero();
    assertThat(escrowOf(owner)).isZero();
  }

  @Test
  void lockOwnerSerializesCountThenInsertAcrossServers() throws Exception {
    UUID owner = UUID.randomUUID();
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    int limit = 3;
    AtomicInteger created = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          boolean ok =
              tx(
                  s,
                  (UnitOfWork u) -> {
                    u.orders().lockOwner(owner);
                    if (u.orders().countOpenByOwner(owner) >= limit) {
                      return false;
                    }
                    // Absichtlich Zeit zwischen Zaehlen und Anlegen, damit ohne Sperre Races
                    // auffallen.
                    try {
                      Thread.sleep(30);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    u.orders().insert(TestData.order(owner, "minecraft:dirt", 1, 1), u.now());
                    return true;
                  });
          if (ok) {
            created.incrementAndGet();
          }
        });
    assertThat(created.get()).isEqualTo(limit);
    assertThat(tx(s1, (UnitOfWork u) -> u.orders().countOpenByOwner(owner))).isEqualTo(limit);
  }

  @Test
  void ownerLimitHoldsWhenTheTransactionAlreadyReadBeforeLocking() throws Exception {
    UUID owner = UUID.randomUUID();
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    int limit = 3;
    AtomicInteger created = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          boolean ok =
              tx(
                  s,
                  (UnitOfWork u) -> {
                    // erste Lesung legt unter REPEATABLE READ den Snapshot fest (wie OrderService)
                    u.journal().findByKey("stale-snapshot-" + owner + "-" + i);
                    u.orders().lockOwner(owner);
                    if (u.orders().countOpenByOwner(owner) >= limit) {
                      return false;
                    }
                    try {
                      Thread.sleep(30);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    u.orders().insert(TestData.order(owner, "minecraft:dirt", 1, 1), u.now());
                    return true;
                  });
          if (ok) {
            created.incrementAndGet();
          }
        });
    assertThat(created.get()).isEqualTo(limit);
  }

  @Test
  void lockOwnerIsIdempotentAndLeavesNoEscrow() {
    UUID owner = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().lockOwner(owner);
          u.orders().lockOwner(owner);
          return null;
        });
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().lockOwner(owner);
          return null;
        });
    assertThat(escrowOf(owner)).isZero();
  }

  // ---------------------------------------------------------------- Journal

  @Test
  void journalInsertIfAbsentIsIdempotent() {
    UUID p = UUID.randomUUID();
    JournalEntry first = TestData.journal("delivery:1:x:1", p, null);
    var r1 = tx(storage(), (UnitOfWork u) -> u.journal().insertIfAbsent(first));
    assertThat(r1.created()).isTrue();
    assertThat(r1.entry()).isEqualTo(first);
    JournalEntry again = TestData.journal("delivery:1:x:1", p, null);
    var r2 = tx(storage(), (UnitOfWork u) -> u.journal().insertIfAbsent(again));
    assertThat(r2.created()).isFalse();
    assertThat(r2.entry().id()).isEqualTo(first.id());
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findByKey("delivery:1:x:1")))
        .contains(first);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().find(first.id()))).contains(first);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().find(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void journalEntryWithoutItemRoundTrips() {
    UUID p = UUID.randomUUID();
    JournalEntry e =
        JournalEntry.created(
            "refund:7",
            null,
            p,
            "srv-a",
            dev.orderhub.tx.TxType.CANCEL_REFUND,
            null,
            0,
            123,
            TestData.T0);
    tx(storage(), (UnitOfWork u) -> u.journal().insertIfAbsent(e));
    JournalEntry loaded = tx(storage(), (UnitOfWork u) -> u.journal().find(e.id())).orElseThrow();
    assertThat(loaded.item()).isNull();
    assertThat(loaded.orderId()).isNull();
    assertThat(loaded).isEqualTo(e);
  }

  @Test
  void journalInsertIfAbsentWithSameKeyFromManyThreadsCreatesOne() throws Exception {
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    UUID p = UUID.randomUUID();
    Set<UUID> ids = ConcurrentHashMap.newKeySet();
    AtomicInteger created = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          var r =
              tx(
                  s,
                  (UnitOfWork u) ->
                      u.journal().insertIfAbsent(TestData.journal("same-key", p, null)));
          ids.add(r.entry().id());
          if (r.created()) {
            created.incrementAndGet();
          }
        });
    assertThat(created.get()).isEqualTo(1);
    assertThat(ids).hasSize(1);
  }

  @Test
  void journalTransitionsAreStrictAndCompareAndSet() {
    UUID p = UUID.randomUUID();
    JournalEntry e = TestData.journal("t1", p, null);
    tx(storage(), (UnitOfWork u) -> u.journal().insertIfAbsent(e));
    assertThatThrownBy(() ->
            tx(
                storage(),
                (UnitOfWork u) -> u.journal().transition(e, TxState.COMMITTED, "skip", 5)))
        .isInstanceOf(InvalidTransitionException.class);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().find(e.id())).orElseThrow().state())
        .isEqualTo(TxState.CREATED);

    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) ->
                    u.journal().transition(e, TxState.PREPARED, "ok", TestData.T0 + 7)))
        .isTrue();
    JournalEntry prepared = tx(storage(), (UnitOfWork u) -> u.journal().find(e.id())).orElseThrow();
    assertThat(prepared.state()).isEqualTo(TxState.PREPARED);
    assertThat(prepared.version()).isEqualTo(1);
    assertThat(prepared.updatedAt()).isEqualTo(TestData.T0 + 7);
    assertThat(prepared.detail()).isEqualTo("ok");
    // Veralteter Stand (state/version) -> false
    assertThat(
            txBool(
                storage(), (UnitOfWork u) -> u.journal().transition(e, TxState.FAILED, "stale", 9)))
        .isFalse();
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().find(e.id())).orElseThrow().state())
        .isEqualTo(TxState.PREPARED);
    // MANUAL_REVIEW aus nicht-terminalem Zustand erlaubt
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) ->
                    u.journal().transition(prepared, TxState.MANUAL_REVIEW, null, 10)))
        .isTrue();
  }

  @Test
  void journalConcurrentTransitionHasExactlyOneWinner() throws Exception {
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    JournalEntry e = TestData.journal("race", UUID.randomUUID(), null);
    tx(s1, (UnitOfWork u) -> u.journal().insertIfAbsent(e));
    AtomicInteger wins = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          if (tx(
              s,
              (UnitOfWork u) -> u.journal().transition(e, TxState.PREPARED, "srv" + i, u.now()))) {
            wins.incrementAndGet();
          }
        });
    assertThat(wins.get()).isEqualTo(1);
  }

  @Test
  void journalQueries() {
    UUID p = UUID.randomUUID();
    UUID q = UUID.randomUUID();
    Order o =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(p, "minecraft:diamond", 5, 5), TestData.T0));
    JournalEntry a = TestData.journal("qa", p, o.id());
    JournalEntry b = TestData.journal("qb", q, o.id());
    JournalEntry c = TestData.journal("qc", p, null);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.journal().insertIfAbsent(a);
          u.journal().insertIfAbsent(b);
          u.journal().insertIfAbsent(c);
          u.journal().transition(b, TxState.PREPARED, null, TestData.T0 + 10);
          return null;
        });
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.journal().findByStates(EnumSet.of(TxState.CREATED), 10)))
        .extracting(JournalEntry::idempotencyKey)
        .containsExactlyInAnyOrder("qa", "qc");
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.journal().findByStates(EnumSet.of(TxState.PREPARED), 10)))
        .hasSize(1);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.journal().findByStates(EnumSet.noneOf(TxState.class), 10)))
        .isEmpty();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.journal().findByStates(EnumSet.of(TxState.CREATED), 1)))
        .hasSize(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findByPlayer(p, 0, 10))).hasSize(2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findByPlayer(p, 1, 10))).hasSize(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findByOrder(o.id()))).hasSize(2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findRecent(0, 2))).hasSize(2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findRecent(2, 10))).hasSize(1);
  }

  // ---------------------------------------------------------------- Ledger

  @Test
  void ledgerAppendsAndReads() {
    UUID tx = UUID.randomUUID();
    UUID p = UUID.randomUUID();
    var e1 =
        new LedgerRepository.Entry(
            UUID.randomUUID(), tx, p, LedgerRepository.Kind.PAYOUT, 900, TestData.T0, "gross");
    var e2 =
        new LedgerRepository.Entry(
            UUID.randomUUID(), tx, null, LedgerRepository.Kind.SALES_TAX, 100, TestData.T0, null);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.ledger().append(e1);
          u.ledger().append(e2);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.ledger().findByTx(tx))).containsExactly(e1, e2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.ledger().findByPlayer(p, 0, 10)))
        .containsExactly(e1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.ledger().findByPlayer(p, 1, 10))).isEmpty();
    assertThatThrownBy(() ->
            tx(
                storage(),
                (UnitOfWork u) -> {
                  u.ledger().append(e1);
                  return null;
                }))
        .isInstanceOf(StorageException.class);
  }

  @Test
  void ledgerIsAppendOnlyEnforcedByTriggers() throws Exception {
    UUID tx = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.ledger()
              .append(
                  new LedgerRepository.Entry(
                      UUID.randomUUID(),
                      tx,
                      null,
                      LedgerRepository.Kind.REFUND,
                      5,
                      TestData.T0,
                      null));
          return null;
        });
    try (Connection c = rawDataSource().getConnection();
        Statement st = c.createStatement()) {
      assertThatThrownBy(() -> st.executeUpdate("UPDATE transactions SET amount = 1"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM transactions"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("append-only");
    }
    assertThat(tx(storage(), (UnitOfWork u) -> u.ledger().findByTx(tx))).hasSize(1);
  }

  // ---------------------------------------------------------------- Payouts

  @Test
  void payoutLifecycle() {
    UUID txId = UUID.randomUUID();
    UUID p = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.payouts().enqueue(txId, p, PayoutRepository.Kind.PAYOUT, 700, TestData.T0);
          u.payouts()
              .enqueue(txId, p, PayoutRepository.Kind.PAYOUT, 999, TestData.T0 + 1); // idempotent
          u.payouts().enqueue(txId, p, PayoutRepository.Kind.REFUND, 50, TestData.T0);
          return null;
        });
    var pending =
        tx(storage(), (UnitOfWork u) -> u.payouts().findByTx(txId, PayoutRepository.Kind.PAYOUT))
            .orElseThrow();
    assertThat(pending.amount()).isEqualTo(700);
    assertThat(pending.state()).isEqualTo(PayoutRepository.State.PENDING);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.payouts().findByState(PayoutRepository.State.PENDING, 10)))
        .hasSize(2);

    var claimed = tx(storage(), (UnitOfWork u) -> u.payouts().claimBatch(1, TestData.T0 + 5));
    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).state()).isEqualTo(PayoutRepository.State.IN_PROGRESS);
    assertThat(claimed.get(0).attempts()).isEqualTo(1);
    var item = claimed.get(0);
    assertThat(txBool(storage(), (UnitOfWork u) -> u.payouts().release(item.id(), TestData.T0 + 6)))
        .isTrue();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.payouts().release(item.id(), TestData.T0 + 6)))
        .isFalse();
    var again = tx(storage(), (UnitOfWork u) -> u.payouts().claimBatch(10, TestData.T0 + 7));
    assertThat(again).hasSize(2);
    assertThat(again).extracting(PayoutRepository.Item::attempts).contains(2, 1);
    var payout =
        again.stream()
            .filter(i -> i.kind() == PayoutRepository.Kind.PAYOUT)
            .findFirst()
            .orElseThrow();
    var refund =
        again.stream()
            .filter(i -> i.kind() == PayoutRepository.Kind.REFUND)
            .findFirst()
            .orElseThrow();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.payouts().markCompleted(payout.id(), TestData.T0 + 8)))
        .isTrue();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.payouts().markCompleted(payout.id(), TestData.T0 + 8)))
        .isFalse();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.payouts().markReview(refund.id(), "unclear", TestData.T0 + 9)))
        .isTrue();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.payouts().findByState(PayoutRepository.State.REVIEW, 10)))
        .hasSize(1);
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.payouts().findByState(PayoutRepository.State.COMPLETED, 10)))
        .hasSize(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.payouts().claimBatch(10, TestData.T0 + 10)))
        .isEmpty();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.payouts().markCompleted(UUID.randomUUID(), 1)))
        .isFalse();
  }

  @Test
  void markReviewIfUnchangedIsCompareAndSetOnUpdatedAt() {
    UUID txId = UUID.randomUUID();
    UUID p = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.payouts().enqueue(txId, p, PayoutRepository.Kind.PAYOUT, 100, TestData.T0);
          return null;
        });
    PayoutRepository.Item claimed =
        tx(storage(), (UnitOfWork u) -> u.payouts().claimBatch(10, TestData.T0 + 1)).stream()
            .filter(i -> i.txId().equals(txId))
            .findFirst()
            .orElseThrow();
    // Veralteter Zeitstempel (ein anderer Server hat die Zeile fortgeschrieben): keine Wirkung.
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) ->
                    u.payouts()
                        .markReviewIfUnchanged(
                            claimed.id(), claimed.updatedAt() - 1, "stale", TestData.T0 + 5)))
        .isFalse();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) ->
                    u.payouts()
                        .markReviewIfUnchanged(
                            claimed.id(), claimed.updatedAt(), "unclear", TestData.T0 + 6)))
        .isTrue();
  }

  @Test
  void payoutClaimBatchWithEightThreadsClaimsEveryPaymentExactlyOnce() throws Exception {
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    int n = 240;
    tx(
        s1,
        (UnitOfWork u) -> {
          for (int i = 0; i < n; i++) {
            PayoutRepository.Kind kind =
                i % 4 == 0 ? PayoutRepository.Kind.REFUND : PayoutRepository.Kind.PAYOUT;
            u.payouts()
                .enqueue(UUID.randomUUID(), UUID.randomUUID(), kind, 10 + i, TestData.T0 + i);
          }
          return null;
        });
    List<UUID> claimedIds = Collections.synchronizedList(new ArrayList<>());
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          while (true) {
            var batch = tx(s, (UnitOfWork u) -> u.payouts().claimBatch(7, u.now()));
            if (batch.isEmpty()) {
              return;
            }
            batch.forEach(item -> claimedIds.add(item.id()));
          }
        });
    assertThat(claimedIds).hasSize(n);
    assertThat(new HashSet<>(claimedIds)).hasSize(n);
    assertThat(
            tx(
                s1,
                (UnitOfWork u) ->
                    u.payouts().findByState(PayoutRepository.State.IN_PROGRESS, 1000)))
        .hasSize(n);
    assertThat(
            tx(s1, (UnitOfWork u) -> u.payouts().findByState(PayoutRepository.State.PENDING, 1000)))
        .isEmpty();
  }

  // ---------------------------------------------------------------- Parcels

  @Test
  void parcelAddIsIdempotentAndListsAvailable() {
    UUID owner = UUID.randomUUID();
    UUID src = UUID.randomUUID();
    long id1 =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.claims()
                    .add(owner, null, TestData.item("minecraft:diamond"), 10, src, TestData.T0));
    long id2 =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.claims()
                    .add(
                        owner, null, TestData.item("minecraft:diamond"), 99, src, TestData.T0 + 1));
    assertThat(id2).isEqualTo(id1);
    var parcel = tx(storage(), (UnitOfWork u) -> u.claims().find(id1)).orElseThrow();
    assertThat(parcel.quantity()).isEqualTo(10);
    assertThat(parcel.state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(parcel.item()).isEqualTo(TestData.item("minecraft:diamond"));
    assertThat(parcel.reservedBy()).isNull();
    assertThat(parcel.orderId()).isNull();
    long other =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.claims()
                    .add(
                        owner,
                        null,
                        TestData.item("minecraft:coal"),
                        1,
                        UUID.randomUUID(),
                        TestData.T0 + 2));
    assertThat(other).isNotEqualTo(id1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().countAvailable(owner))).isEqualTo(2);
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().listAvailable(owner, 0, 10)))
        .extracting(ParcelRepository.Parcel::id)
        .containsExactly(id1, other);
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().listAvailable(owner, 1, 10))).hasSize(1);
    // claims und returns sind getrennte Tabellen
    assertThat(tx(storage(), (UnitOfWork u) -> u.returns().countAvailable(owner))).isZero();
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().find(id1 + 1000))).isEmpty();
  }

  @Test
  void parcelReserveAndSettlePartial() {
    UUID owner = UUID.randomUUID();
    UUID txA = UUID.randomUUID();
    Order o =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders().insert(TestData.order(owner, "minecraft:diamond", 5, 5), TestData.T0));
    long id =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.claims()
                    .add(
                        owner,
                        o.id(),
                        TestData.item("minecraft:diamond"),
                        10,
                        UUID.randomUUID(),
                        TestData.T0));
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().reserve(id, txA, TestData.T0 + 1)))
        .isTrue();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.claims().reserve(id, UUID.randomUUID(), TestData.T0 + 2)))
        .isFalse();
    var reserved = tx(storage(), (UnitOfWork u) -> u.claims().find(id)).orElseThrow();
    assertThat(reserved.state()).isEqualTo(ParcelRepository.State.RESERVED);
    assertThat(reserved.reservedBy()).isEqualTo(txA);
    assertThat(reserved.orderId()).isEqualTo(o.id());
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().findReserved(10))).hasSize(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().countAvailable(owner))).isZero();
    // falscher tx
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.claims().settle(id, UUID.randomUUID(), 4, TestData.T0 + 3)))
        .isFalse();
    // mehr als vorhanden
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().settle(id, txA, 11, TestData.T0 + 3)))
        .isFalse();
    // Teilmenge: 4 von 10 -> 6 bleiben, wieder AVAILABLE
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().settle(id, txA, 4, TestData.T0 + 4)))
        .isTrue();
    var partial = tx(storage(), (UnitOfWork u) -> u.claims().find(id)).orElseThrow();
    assertThat(partial.quantity()).isEqualTo(6);
    assertThat(partial.state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(partial.reservedBy()).isNull();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().settle(id, txA, 1, TestData.T0 + 5)))
        .isFalse();
    // Rest: ganz uebergeben -> CLAIMED
    UUID txB = UUID.randomUUID();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().reserve(id, txB, TestData.T0 + 6)))
        .isTrue();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.claims().settle(id, txB, 6, TestData.T0 + 7)))
        .isTrue();
    var done = tx(storage(), (UnitOfWork u) -> u.claims().find(id)).orElseThrow();
    assertThat(done.quantity()).isZero();
    assertThat(done.state()).isEqualTo(ParcelRepository.State.CLAIMED);
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.claims().reserve(id, UUID.randomUUID(), TestData.T0 + 8)))
        .isFalse();
    assertThat(tx(storage(), (UnitOfWork u) -> u.claims().countAvailable(owner))).isZero();
  }

  @Test
  void parcelReleaseReservation() {
    UUID owner = UUID.randomUUID();
    UUID txA = UUID.randomUUID();
    long id =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.returns()
                    .add(
                        owner,
                        null,
                        TestData.item("minecraft:dirt"),
                        3,
                        UUID.randomUUID(),
                        TestData.T0));
    assertThat(txBool(storage(), (UnitOfWork u) -> u.returns().releaseReservation(id, txA, 1)))
        .isFalse();
    tx(storage(), (UnitOfWork u) -> u.returns().reserve(id, txA, 2));
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.returns().releaseReservation(id, UUID.randomUUID(), 3)))
        .isFalse();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.returns().releaseReservation(id, txA, 4)))
        .isTrue();
    var p = tx(storage(), (UnitOfWork u) -> u.returns().find(id)).orElseThrow();
    assertThat(p.state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(p.quantity()).isEqualTo(3);
    assertThat(tx(storage(), (UnitOfWork u) -> u.returns().findReserved(10))).isEmpty();
  }

  @Test
  void parcelConcurrentReserveHasOneWinner() throws Exception {
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    UUID owner = UUID.randomUUID();
    long id =
        tx(
            s1,
            (UnitOfWork u) ->
                u.claims()
                    .add(
                        owner,
                        null,
                        TestData.item("minecraft:gold_ingot"),
                        5,
                        UUID.randomUUID(),
                        TestData.T0));
    AtomicInteger wins = new AtomicInteger();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          if (tx(s, (UnitOfWork u) -> u.claims().reserve(id, UUID.randomUUID(), u.now()))) {
            wins.incrementAndGet();
          }
        });
    assertThat(wins.get()).isEqualTo(1);
  }

  @Test
  void concurrentParcelAddForSameSourceCreatesOneRow() throws Exception {
    JdbcStorage s1 = server("srv-a");
    JdbcStorage s2 = server("srv-b");
    UUID owner = UUID.randomUUID();
    UUID src = UUID.randomUUID();
    Set<Long> ids = ConcurrentHashMap.newKeySet();
    runConcurrently(
        THREADS,
        i -> {
          Storage s = i % 2 == 0 ? s1 : s2;
          ids.add(
              tx(
                  s,
                  (UnitOfWork u) ->
                      u.claims()
                          .add(owner, null, TestData.item("minecraft:dirt"), 2, src, u.now())));
        });
    assertThat(ids).hasSize(1);
    assertThat(tx(s1, (UnitOfWork u) -> u.claims().countAvailable(owner))).isEqualTo(1);
  }

  // ---------------------------------------------------------------- transact()

  @Test
  void exceptionRollsBackEverything() {
    UUID owner = UUID.randomUUID();
    assertThatThrownBy(() ->
            tx(
                storage(),
                (UnitOfWork u) -> {
                  u.orders().insert(TestData.order(owner, "minecraft:diamond", 5, 5), TestData.T0);
                  u.ledger()
                      .append(
                          new LedgerRepository.Entry(
                              UUID.randomUUID(),
                              UUID.randomUUID(),
                              owner,
                              LedgerRepository.Kind.REFUND,
                              1,
                              TestData.T0,
                              null));
                  throw new IllegalStateException("boom");
                }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().count(OrderQuery.firstPage()))).isZero();
    assertThat(tx(storage(), (UnitOfWork u) -> u.ledger().findByPlayer(owner, 0, 10))).isEmpty();
    assertThat(escrowOf(owner)).isZero();
    // Der Storage bleibt benutzbar.
    assertThat(tx(storage(), (UnitOfWork u) -> 42)).isEqualTo(42);
  }

  @Test
  void invalidTransitionInsideTransactionRollsBack() {
    UUID p = UUID.randomUUID();
    JournalEntry e = TestData.journal("rb", p, null);
    assertThatThrownBy(() ->
            tx(
                storage(),
                (UnitOfWork u) -> {
                  u.journal().insertIfAbsent(e);
                  u.journal().transition(e, TxState.CLAIM_COMPLETED, null, 1);
                  return null;
                }))
        .isInstanceOf(InvalidTransitionException.class);
    assertThat(tx(storage(), (UnitOfWork u) -> u.journal().findByKey("rb"))).isEmpty();
  }

  @Test
  void retriesOnSimulatedLockConflictAndEventuallySucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    String result =
        tx(
            storage(),
            (UnitOfWork u) -> {
              u.orders().count(OrderQuery.firstPage());
              if (attempts.incrementAndGet() < 3) {
                throw new StorageException(new SQLException("Deadlock found", "40001", 1213));
              }
              return "ok";
            });
    assertThat(result).isEqualTo("ok");
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void retriesOnSqliteBusyAndLockWaitTimeout() {
    AtomicInteger attempts = new AtomicInteger();
    tx(
        storage(),
        (UnitOfWork u) -> {
          int n = attempts.incrementAndGet();
          if (n == 1) {
            throw new StorageException(
                new SQLException("[SQLITE_BUSY] The database file is locked", null, 5));
          }
          if (n == 2) {
            throw new StorageException(
                new SQLException("Lock wait timeout exceeded", "HY000", 1205));
          }
          return null;
        });
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void givesUpAfterMaxAttemptsWithOriginalError() throws Exception {
    JdbcStorage s =
        open("srv-x", StorageConfig.defaults("srv-x").withMaxAttempts(3).withBackoff(1, 2));
    opened.add(s);
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(() ->
            tx(
                s,
                (UnitOfWork u) -> {
                  attempts.incrementAndGet();
                  throw new StorageException(new SQLException("Deadlock found", "40001", 1213));
                }))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Deadlock");
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void nonTransientErrorIsNotRetried() {
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(() ->
            tx(
                storage(),
                (UnitOfWork u) -> {
                  attempts.incrementAndGet();
                  throw new StorageException(new SQLException("syntax", "42000", 1064));
                }))
        .isInstanceOf(StorageException.class);
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  void workRunsOnNamedDbThreadsAndSharesOneClockPerTransaction() {
    var info =
        tx(
            storage(),
            (UnitOfWork u) ->
                new Object[] {
                  Thread.currentThread().getName(),
                  Thread.currentThread().isDaemon(),
                  u.now() == u.now()
                });
    assertThat((String) info[0]).startsWith("orderhub-db-");
    assertThat(info[1]).isEqualTo(true);
    assertThat(info[2]).isEqualTo(true);
    assertThat(storage().serverId()).isEqualTo("srv-a");
  }

  @Test
  void closeShutsDownAndRejectsNewWork() throws Exception {
    JdbcStorage s = open("srv-c", StorageConfig.defaults("srv-c"));
    assertThat(tx(s, (UnitOfWork u) -> 1)).isEqualTo(1);
    s.close();
    assertThatThrownBy(() -> s.transact((UnitOfWork u) -> 1).get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
  }

  // ---------------------------------------------------------------- Helfer

  private long escrowOf(UUID owner) {
    try (Connection c = rawDataSource().getConnection();
        var ps = c.prepareStatement("SELECT reserved FROM escrow_accounts WHERE player_uuid = ?")) {
      ps.setString(1, owner.toString());
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Startet {@code threads} Threads gleichzeitig (Startsignal) und wartet auf alle. */
  static void runConcurrently(int threads, ThrowingIntConsumer body) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int index = i;
        futures.add(
            pool.submit(() -> {
              ready.countDown();
              go.await();
              body.accept(index);
              return null;
            }));
      }
      ready.await();
      go.countDown();
      for (Future<?> f : futures) {
        f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------ Insights und Outbox

  @Test
  void statisticsAccumulateAndAreCreatedOnFirstUse() {
    UUID supplier = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights().recordOrderCreated(buyer, TestData.T0);
          u.insights().recordDelivery(supplier, buyer, 10, 1000, 980, TestData.T0 + 1);
          u.insights().recordDelivery(supplier, buyer, 5, 500, 490, TestData.T0 + 2);
          u.insights().recordOrderCompleted(buyer, TestData.T0 + 3);
          return null;
        });
    var s = tx(storage(), (UnitOfWork u) -> u.insights().stats(supplier)).orElseThrow();
    assertThat(s.itemsDelivered()).isEqualTo(15);
    assertThat(s.moneyEarned()).isEqualTo(1470);
    var b = tx(storage(), (UnitOfWork u) -> u.insights().stats(buyer)).orElseThrow();
    assertThat(b.itemsReceived()).isEqualTo(15);
    assertThat(b.moneySpent()).isEqualTo(1500);
    assertThat(b.ordersCreated()).isEqualTo(1);
    assertThat(b.ordersCompleted()).isEqualTo(1);
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().stats(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void tradesAreRecordedOnceAndFilteredByItemAndTime() {
    UUID journal = UUID.randomUUID();
    boolean first =
        txBool(
            storage(),
            (UnitOfWork u) ->
                u.insights()
                    .recordTrade(journal, 1, "minecraft:diamond", 800, 4, TestData.T0 + 10));
    boolean again =
        txBool(
            storage(),
            (UnitOfWork u) ->
                u.insights()
                    .recordTrade(journal, 1, "minecraft:diamond", 800, 4, TestData.T0 + 10));
    assertThat(first).isTrue();
    assertThat(again).isFalse();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights()
              .recordTrade(UUID.randomUUID(), 2, "minecraft:diamond", 900, 1, TestData.T0 + 5);
          u.insights().recordTrade(UUID.randomUUID(), 3, "minecraft:coal", 5, 64, TestData.T0 + 20);
          return null;
        });
    var rows =
        tx(
            storage(),
            (UnitOfWork u) -> u.insights().tradesSince("minecraft:diamond", TestData.T0 + 6, 100));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).unitPrice()).isEqualTo(800);
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().purgeTradesBefore(TestData.T0 + 15)))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void openDemandCountsOnlyActiveOrdersOfTheItem() {
    String key = "minecraft:demand-" + UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().insert(TestData.order(UUID.randomUUID(), key, 700, 10), TestData.T0);
          u.orders().insert(TestData.order(UUID.randomUUID(), key, 900, 5), TestData.T0);
          u.orders()
              .insert(TestData.order(UUID.randomUUID(), "minecraft:other", 1, 1), TestData.T0);
          return null;
        });
    var demand = tx(storage(), (UnitOfWork u) -> u.insights().openDemand(key, 50));
    assertThat(demand).hasSize(2);
    assertThat(demand.stream().mapToInt(d -> d.remaining()).sum()).isEqualTo(15);
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().activeItemKeys(500))).contains(key);
  }

  @Test
  void favoritesAlertsAndCooldown() {
    UUID p = UUID.randomUUID();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.insights().addFavorite(p, "minecraft:gold_ingot", TestData.T0)))
        .isTrue();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.insights().addFavorite(p, "minecraft:gold_ingot", TestData.T0)))
        .isFalse();
    assertThat(
            txBool(
                storage(), (UnitOfWork u) -> u.insights().setAlert(p, "minecraft:gold_ingot", 50L)))
        .isTrue();
    assertThat(txBool(storage(), (UnitOfWork u) -> u.insights().setAlert(p, "minecraft:none", 50L)))
        .isFalse();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.insights().alertsFor("minecraft:gold_ingot", 49, TestData.T0)))
        .isEmpty();
    var hits =
        tx(
            storage(),
            (UnitOfWork u) -> u.insights().alertsFor("minecraft:gold_ingot", 60, TestData.T0));
    assertThat(hits).extracting(f -> f.player()).contains(p);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights().touchAlert(p, "minecraft:gold_ingot", TestData.T0 + 100);
          return null;
        });
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.insights().alertsFor("minecraft:gold_ingot", 60, TestData.T0 + 50)))
        .extracting(f -> f.player())
        .doesNotContain(p);
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.insights().removeFavorite(p, "minecraft:gold_ingot")))
        .isTrue();
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().favorites(p))).isEmpty();
  }

  @Test
  void notificationsAreDeduplicatedAndMarkedDelivered() {
    UUID p = UUID.randomUUID();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.insights().addNote(p, "order", "a", "k1", TestData.T0)))
        .isTrue();
    assertThat(
            txBool(
                storage(),
                (UnitOfWork u) -> u.insights().addNote(p, "order", "a", "k1", TestData.T0)))
        .isFalse();
    var pending = tx(storage(), (UnitOfWork u) -> u.insights().pendingNotes(p, 10));
    assertThat(pending).hasSize(1);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights()
              .markNotesDelivered(pending.stream().map(n -> n.id()).toList(), TestData.T0 + 1);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().pendingNotes(p, 10))).isEmpty();
  }

  @Test
  void releasedNotesAreOpenAgainAndOnlyDeliveredOnesAreTouched() {
    UUID p = UUID.randomUUID();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights().addNote(p, "order", "a", "r1", TestData.T0);
          u.insights().addNote(p, "order", "b", "r2", TestData.T0);
          return null;
        });
    var pending = tx(storage(), (UnitOfWork u) -> u.insights().pendingNotes(p, 10));
    var ids = pending.stream().map(n -> n.id()).toList();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights().claimNotes(ids, TestData.T0 + 1);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().pendingNotes(p, 10))).isEmpty();

    tx(
        storage(),
        (UnitOfWork u) -> {
          u.insights().releaseNotes(List.of(ids.get(0)));
          return null;
        });

    assertThat(tx(storage(), (UnitOfWork u) -> u.insights().pendingNotes(p, 10)))
        .extracting(n -> n.id())
        .containsExactly(ids.get(0));
  }

  @Test
  void discordOutboxLifecycleWithBackoff() {
    String payload = "{\"x\":\"" + UUID.randomUUID() + "\"}";
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().enqueueDiscord(payload, TestData.T0);
          return null;
        });
    var due = tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(TestData.T0, 50));
    var mine = due.stream().filter(i -> i.payload().equals(payload)).findFirst().orElseThrow();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().discordRetry(mine.id(), 1, TestData.T0 + 5000, "http 503", TestData.T0 + 1);
          return null;
        });
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(TestData.T0 + 1000, 50)).stream()
                .anyMatch(i -> i.id() == mine.id()))
        .isFalse();
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(TestData.T0 + 6000, 50)).stream()
                .anyMatch(i -> i.id() == mine.id() && i.attempts() == 1))
        .isTrue();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().discordSent(mine.id(), TestData.T0 + 7000);
          return null;
        });
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(TestData.T0 + 99_000, 50))
                .stream()
                .anyMatch(i -> i.id() == mine.id()))
        .isFalse();
  }

  @Test
  void cacheEventsAreVisibleToOtherServersOnly() {
    long before = tx(storage(), (UnitOfWork u) -> u.outbox().latestCacheEventId());
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().publishCacheEvent("orders", "1", "server-a", TestData.T0);
          u.outbox().publishCacheEvent("orders", "2", "server-b", TestData.T0 + 1);
          return null;
        });
    var seenByA =
        tx(storage(), (UnitOfWork u) -> u.outbox().cacheEventsAfter(before, "server-a", 50));
    assertThat(seenByA).extracting(e -> e.payload()).containsExactly("2");
    var seenByB =
        tx(storage(), (UnitOfWork u) -> u.outbox().cacheEventsAfter(before, "server-b", 50));
    assertThat(seenByB).extracting(e -> e.payload()).containsExactly("1");
  }

  @FunctionalInterface
  interface ThrowingIntConsumer {
    void accept(int i) throws Exception;
  }

  @Test
  void deliveryHoldsAreIdempotentAndFinishOnlyOnce() throws Exception {
    java.util.UUID tx = java.util.UUID.randomUUID();
    java.util.UUID owner = java.util.UUID.randomUUID();
    var item = TestData.item("minecraft:dirt");
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.holds().add(tx, owner, null, item, 7, TestData.T0);
          u.holds().add(tx, owner, null, item, 99, TestData.T0 + 1); // Wiederholung: wirkungslos
          return null;
        });
    var hold = tx(storage(), (UnitOfWork u) -> u.holds().find(tx)).orElseThrow();
    assertThat(hold.quantity()).isEqualTo(7);
    assertThat(hold.state()).isEqualTo(HoldRepository.State.HELD);
    assertThat(hold.item().itemKey()).isEqualTo("minecraft:dirt");
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.holds().finish(tx, HoldRepository.State.RELEASED, TestData.T0 + 2)))
        .isTrue();
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.holds().finish(tx, HoldRepository.State.CONSUMED, TestData.T0 + 3)))
        .isFalse();
    assertThat(tx(storage(), (UnitOfWork u) -> u.holds().find(tx)).orElseThrow().state())
        .isEqualTo(HoldRepository.State.RELEASED);
  }

  @Test
  void discordClaimIsExclusiveUntilItExpiresAndReleasedByRetry() throws Exception {
    long now = TestData.T0;
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().enqueueDiscord("{}", now);
          return null;
        });
    long id = tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(now, 10)).get(0).id();
    assertThat(tx(storage(), (UnitOfWork u) -> u.outbox().claimDiscord(id, "a", now, now + 1000)))
        .isTrue();
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.outbox().claimDiscord(id, "b", now + 10, now + 1010)))
        .isFalse();
    assertThat(tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(now + 10, 10))).isEmpty();
    // abgelaufener Claim (Absturz des ersten Servers): der zweite darf uebernehmen
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) -> u.outbox().claimDiscord(id, "b", now + 2000, now + 3000)))
        .isTrue();
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.outbox().discordRetry(id, 1, now + 2500, "x", now + 2100);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.outbox().dueDiscord(now + 2600, 10))).hasSize(1);
  }

  // ---------------------------------------------------------------- V5: Optionen, Vorlagen, Keyset

  @Test
  void orderOptionsRoundTripAndDefaultToExact() {
    UUID a = UUID.randomUUID();
    long id =
        tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().insert(TestData.order(a, "minecraft:diamond", 100, 3), TestData.T0))
            .id();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().options(id)))
        .isEqualTo(dev.orderhub.domain.OrderOptions.EXACT);
    var custom = new dev.orderhub.domain.OrderOptions(60, true, true);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().saveOptions(id, custom);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().options(id))).isEqualTo(custom);
    var changed = new dev.orderhub.domain.OrderOptions(0, false, false);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().saveOptions(id, changed);
          return null;
        });
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().options(id))).isEqualTo(changed);
  }

  @Test
  void optionsForReturnsOneEntryPerOrderInASingleCall() {
    UUID a = UUID.randomUUID();
    long first =
        tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().insert(TestData.order(a, "minecraft:diamond", 100, 3), TestData.T0))
            .id();
    long second =
        tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .insert(TestData.order(a, "minecraft:gold_ingot", 100, 3), TestData.T0))
            .id();
    var custom = new dev.orderhub.domain.OrderOptions(60, true, true);
    tx(
        storage(),
        (UnitOfWork u) -> {
          u.orders().saveOptions(second, custom);
          return null;
        });

    var found =
        tx(storage(), (UnitOfWork u) -> u.orders().optionsFor(List.of(first, second, 999_999L)));

    assertThat(found).doesNotContainKey(first);
    assertThat(found).containsEntry(second, custom);
    assertThat(found).doesNotContainKey(999_999L);
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().optionsFor(List.of()))).isEmpty();
  }

  @Test
  void checkConstraintRejectsOutOfRangeDurabilityOption() {
    UUID a = UUID.randomUUID();
    long id =
        tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders().insert(TestData.order(a, "minecraft:diamond", 100, 3), TestData.T0))
            .id();
    var bad =
        assertThatThrownBy(() ->
            rawSql(
                "INSERT INTO order_options(order_id, tol_durability, tol_ench_min, auto_claim) VALUES ("
                    + id
                    + ", 101, 0, 0)"));
    bad.isInstanceOf(Exception.class);
  }

  private void rawSql(String sql) throws Exception {
    try (Connection c = rawDataSource().getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(sql);
    }
  }

  private dev.orderhub.domain.OrderTemplate template(UUID owner, String name, long price) {
    return new dev.orderhub.domain.OrderTemplate(
        0,
        owner,
        name,
        TestData.item("minecraft:diamond"),
        price,
        64,
        8,
        72,
        new dev.orderhub.domain.OrderOptions(80, true, false),
        true,
        TestData.T0);
  }

  @Test
  void templatesAreSavedReplacedLimitedAndDeletedPerPlayer() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().save(template(a, "one", 100), 2)))
        .isTrue();
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().save(template(a, "two", 200), 2)))
        .isTrue();
    // Limit erreicht: neue Vorlage abgelehnt, gleichnamige ersetzt
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().save(template(a, "three", 300), 2)))
        .isFalse();
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().save(template(a, "one", 150), 2)))
        .isTrue();
    assertThat(
            tx(storage(), (UnitOfWork u) -> u.templates().find(a, "one")).orElseThrow().unitPrice())
        .isEqualTo(150);
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().list(a)))
        .extracting(dev.orderhub.domain.OrderTemplate::name)
        .containsExactly("one", "two");
    // andere Spieler sehen nichts, Namen sind je Spieler eindeutig
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().list(b))).isEmpty();
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().save(template(b, "one", 1), 2)))
        .isTrue();
    var loaded = tx(storage(), (UnitOfWork u) -> u.templates().find(a, "two")).orElseThrow();
    assertThat(loaded.options()).isEqualTo(new dev.orderhub.domain.OrderOptions(80, true, false));
    assertThat(loaded.autoRenew()).isTrue();
    assertThat(loaded.item().data()).isEqualTo(TestData.item("minecraft:diamond").data());
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().delete(a, "one"))).isTrue();
    assertThat(tx(storage(), (UnitOfWork u) -> u.templates().delete(a, "one"))).isFalse();
  }

  /**
   * 14 Auftraege mit bewusst gleichen Schluesseln (Preis, Fuellstand), damit Gleichstaende zaehlen.
   */
  private void seedForPaging() {
    UUID a = UUID.randomUUID();
    String[] keys = {
      "minecraft:diamond", "minecraft:diamond_sword", "minecraft:iron_ingot", "minecraft:stick"
    };
    for (int i = 0; i < 14; i++) {
      final int n = i;
      tx(
          storage(),
          (UnitOfWork u) -> {
            Order o =
                u.orders()
                    .insert(
                        new NewOrder(
                            a,
                            "srv-a",
                            TestData.item(keys[n % keys.length]),
                            100L + (n % 3) * 50,
                            10 + (n % 2) * 5,
                            1,
                            TestData.T0 + 100_000,
                            false,
                            n % 2 == 0 ? "ores" : "tools"),
                        TestData.T0 + (n % 4));
            int filled = n % 5;
            if (filled > 0) {
              u.orders().update(o.afterDelivery(filled, TestData.T0 + 50));
            }
            return null;
          });
    }
  }

  @Test
  void keysetPagesEqualOffsetPagesForEverySortWithTies() {
    seedForPaging();
    for (OrderQuery.Sort sort : OrderQuery.Sort.values()) {
      List<Long> viaOffset = new ArrayList<>();
      for (int offset = 0; offset < 14; offset += 4) {
        int off = offset;
        viaOffset.addAll(
            tx(
                    storage(),
                    (UnitOfWork u) ->
                        u.orders().search(new OrderQuery(null, null, null, null, sort, off, 4)))
                .stream()
                .map(Order::id)
                .toList());
      }
      List<Long> viaKeyset = new ArrayList<>();
      OrderQuery.Cursor cursor = null;
      for (int page = 0; page < 10; page++) {
        OrderQuery.Cursor after = cursor;
        List<Order> rows =
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(new OrderQuery(null, null, null, null, sort, 0, 4, null, after)));
        if (rows.isEmpty()) {
          break;
        }
        rows.forEach(o -> viaKeyset.add(o.id()));
        cursor = OrderQuery.Cursor.of(sort, rows.get(rows.size() - 1));
      }
      assertThat(viaKeyset).as("keyset %s", sort).containsExactlyElementsOf(viaOffset);
      assertThat(viaKeyset).as("all rows %s", sort).hasSize(14).doesNotHaveDuplicates();
    }
  }

  @Test
  void textSearchMatchesSubstringsAndCategoryFiltersCombine() {
    seedForPaging();
    var diamonds =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders()
                    .search(new OrderQuery(null, null, null, null, null, 0, 50, "diamond", null)));
    assertThat(diamonds).isNotEmpty().allMatch(o -> o.item().itemKey().contains("diamond"));
    var swordsOnly =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders()
                    .search(
                        new OrderQuery(
                            null, null, null, null, null, 0, 50, "Diamond Sword", null)));
    assertThat(swordsOnly)
        .isNotEmpty()
        .allMatch(o -> o.item().itemKey().equals("minecraft:diamond_sword"));
    var ores =
        tx(
            storage(),
            (UnitOfWork u) ->
                u.orders()
                    .search(
                        new OrderQuery(null, null, "ores", null, null, 0, 50, "diamond", null)));
    assertThat(ores).allMatch(o -> o.category().equals("ores"));
  }

  @Test
  void textSearchTreatsWildcardsAndQuotesAsPlainText() {
    seedForPaging();
    // normalizeText entfernt alles ausser a-z, 0-9, Unterstrich: %, ' und ; erreichen SQL nie
    assertThat(OrderQuery.normalizeText("100%'; DROP TABLE orders;--"))
        .doesNotContain("%", "'", ";");
    assertThat(new OrderQuery(null, null, null, null, null, 0, 50, "%", null).text()).isNull();
    // ein Unterstrich im Suchtext ist kein Platzhalter: "iron_ingot" findet nur Eisenbarren
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, null, 0, 50, "iron_ingot", null))))
        .allMatch(o -> o.item().itemKey().equals("minecraft:iron_ingot"));
    assertThat(
            tx(
                storage(),
                (UnitOfWork u) ->
                    u.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, null, null, 0, 50, "iron_ingo_", null))))
        .isEmpty();
    assertThat(tx(storage(), (UnitOfWork u) -> u.orders().count(OrderQuery.firstPage())))
        .isEqualTo(14);
  }

  // ---------------------------------------------------------------- Mehrserver-Szenarien

  private static final UUID MS_OWNER = new UUID(0, 1);
  private static final UUID MS_SUPPLIER_A = new UUID(0, 2);
  private static final UUID MS_SUPPLIER_B = new UUID(0, 3);

  private dev.orderhub.service.OrderService orderService(
      JdbcStorage storage, dev.orderhub.testsupport.InMemoryEconomy economy) {
    return new dev.orderhub.service.OrderService(
        storage,
        economy,
        dev.orderhub.config.Limits.defaults(),
        dev.orderhub.config.Fees.none(),
        new dev.orderhub.config.Expiry(java.time.Duration.ofDays(7), true));
  }

  /** Eine Lieferung von {@code qty}; {@code true}, wenn sie festgeschrieben wurde. */
  private boolean deliverOnce(
      dev.orderhub.tx.DeliveryEngine engine, long orderId, UUID supplier, int qty) {
    var prepared = engine.prepare(orderId, supplier, qty, UUID.randomUUID().toString());
    if (!prepared.ok()) {
      return false;
    }
    engine.confirmItemsRemoved(prepared.journalId());
    return engine.commit(prepared.journalId()).status()
        == dev.orderhub.tx.DeliveryEngine.CommitStatus.COMMITTED;
  }

  @Test
  void twoServersDeliveringToTheSameOrderNeverOverfillNeverPayTwiceAndLoseNothing()
      throws Exception {
    JdbcStorage a = server("srv-a");
    JdbcStorage b = server("srv-b");
    var economy = new dev.orderhub.testsupport.InMemoryEconomy();
    economy.setBalance(MS_OWNER, 10_000_000);
    long orderId = orderService(a, economy).createOrder(msRequest(100, 100)).orderId();
    var engineA = new dev.orderhub.tx.DeliveryEngine(a, dev.orderhub.config.Fees.none());
    var engineB = new dev.orderhub.tx.DeliveryEngine(b, dev.orderhub.config.Fees.none());

    java.util.concurrent.atomic.AtomicInteger committedA =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger committedB =
        new java.util.concurrent.atomic.AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<?> fa =
          pool.submit(() -> {
            start.await();
            for (int i = 0; i < 10; i++) {
              if (deliverOnce(engineA, orderId, MS_SUPPLIER_A, 10)) {
                committedA.incrementAndGet();
              }
            }
            return null;
          });
      Future<?> fb =
          pool.submit(() -> {
            start.await();
            for (int i = 0; i < 10; i++) {
              if (deliverOnce(engineB, orderId, MS_SUPPLIER_B, 10)) {
                committedB.incrementAndGet();
              }
            }
            return null;
          });
      start.countDown();
      fa.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      fb.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    int commits = committedA.get() + committedB.get();
    assertThat(commits).as("20 Versuche zu je 10 gegen 100 Stueck").isEqualTo(10);
    Order order = tx(a, (UnitOfWork u) -> u.orders().find(orderId)).orElseThrow();
    assertThat(order.quantityFilled()).isEqualTo(100);
    assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order.escrowRemaining()).isZero();
    // jeder Treffer hat genau ein Paket fuer den Auftraggeber erzeugt
    int claimed =
        tx(b, (UnitOfWork u) -> u.claims().listAvailable(MS_OWNER, 0, 100)).stream()
            .mapToInt(p -> p.quantity())
            .sum();
    assertThat(claimed).isEqualTo(100);

    // Auszahlungen von beiden Servern gleichzeitig abarbeiten: jede Zeile wird genau einmal bezahlt
    var workerA =
        new dev.orderhub.tx.PayoutWorker(
            a,
            economy,
            java.time.Clock.systemUTC(),
            3,
            java.time.Duration.ofMillis(5),
            java.time.Duration.ofMillis(20));
    var workerB =
        new dev.orderhub.tx.PayoutWorker(
            b,
            economy,
            java.time.Clock.systemUTC(),
            3,
            java.time.Duration.ofMillis(5),
            java.time.Duration.ofMillis(20));
    ExecutorService payers = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (var w : List.of(workerA, workerB)) {
        runs.add(
            payers.submit(() -> {
              for (int i = 0; i < 20; i++) {
                w.runOnce(50);
              }
              return null;
            }));
      }
      for (Future<?> f : runs) {
        f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      payers.shutdownNow();
    }
    assertThat(economy.duplicateReferences()).isEmpty();
    assertThat(economy.balance(MS_SUPPLIER_A) + economy.balance(MS_SUPPLIER_B))
        .as("100 Stueck zu je 100")
        .isEqualTo(10_000);
    assertThat(economy.balance(MS_SUPPLIER_A)).isEqualTo(committedA.get() * 1000L);
    assertThat(economy.balance(MS_SUPPLIER_B)).isEqualTo(committedB.get() * 1000L);
  }

  private dev.orderhub.service.OrderService.CreateOrderRequest msRequest(long price, int qty) {
    return new dev.orderhub.service.OrderService.CreateOrderRequest(
        UUID.randomUUID(),
        MS_OWNER,
        TestData.item("minecraft:diamond"),
        price,
        qty,
        1,
        null,
        false,
        "ores",
        100,
        false);
  }

  @Test
  void aServerThatCrashesMidDeliveryIsRecoveredByTheOtherServerWithoutLossOrDuplicate()
      throws Exception {
    JdbcStorage a = server("srv-a");
    JdbcStorage b = server("srv-b");
    var economy = new dev.orderhub.testsupport.InMemoryEconomy();
    economy.setBalance(MS_OWNER, 10_000_000);
    long orderId = orderService(a, economy).createOrder(msRequest(100, 50)).orderId();
    var engineA = new dev.orderhub.tx.DeliveryEngine(a, dev.orderhub.config.Fees.none());

    // Server A nimmt 7 Items entgegen, bestaetigt die Entnahme und stuerzt vor dem Commit ab.
    var prepared = engineA.prepare(orderId, MS_SUPPLIER_A, 7, "crash-nonce");
    assertThat(prepared.ok()).isTrue();
    engineA.confirmItemsRemoved(prepared.journalId());

    // Server B laeuft weiter und fuehrt seine Wiederherstellung aus.
    Thread.sleep(30);
    var audit = new dev.orderhub.testsupport.Harness.RecordingAudit();
    var recoveryB =
        new dev.orderhub.tx.RecoveryService(
            b,
            new dev.orderhub.tx.ClaimEngine(b, audit),
            new dev.orderhub.tx.ReturnEngine(b, audit),
            audit,
            java.time.Clock.systemUTC(),
            java.time.Duration.ofMillis(10));
    recoveryB.sweep();
    recoveryB.sweep(); // ein zweiter Lauf darf nichts mehr aendern

    assertThat(
            tx(b, (UnitOfWork u) -> u.journal().find(prepared.journalId())).orElseThrow().state())
        .isEqualTo(dev.orderhub.tx.TxState.FAILED);
    var returns = tx(b, (UnitOfWork u) -> u.returns().listAvailable(MS_SUPPLIER_A, 0, 100));
    assertThat(returns).as("genau eine Rueckgabe, keine Verdopplung").hasSize(1);
    assertThat(returns.get(0).quantity()).isEqualTo(7);
    Order order = tx(a, (UnitOfWork u) -> u.orders().find(orderId)).orElseThrow();
    assertThat(order.quantityFilled()).isZero();
    assertThat(order.escrowRemaining()).isEqualTo(5_000);
    assertThat(tx(b, (UnitOfWork u) -> u.claims().listAvailable(MS_OWNER, 0, 100))).isEmpty();
    assertThat(economy.balance(MS_SUPPLIER_A)).isZero();

    // Der abgestuerzte Server startet neu und holt nach: Recovery bleibt idempotent.
    JdbcStorage a2 = server("srv-a");
    var recoveryA =
        new dev.orderhub.tx.RecoveryService(
            a2,
            new dev.orderhub.tx.ClaimEngine(a2, audit),
            new dev.orderhub.tx.ReturnEngine(a2, audit),
            audit,
            java.time.Clock.systemUTC(),
            java.time.Duration.ZERO);
    recoveryA.runAtStartup();
    assertThat(tx(a2, (UnitOfWork u) -> u.returns().listAvailable(MS_SUPPLIER_A, 0, 100)))
        .hasSize(1);
  }

  @Test
  void aNoteIsClaimedExactlyOnceWhenTwoServersReadItAtTheSameTime() throws Exception {
    JdbcStorage a = server("srv-a");
    JdbcStorage b = server("srv-b");
    UUID player = UUID.randomUUID();
    for (int i = 0; i < 30; i++) {
      final int n = i;
      tx(
          a,
          (UnitOfWork u) ->
              u.insights().addNote(player, "delivered", "k|1|" + n, "d" + n, TestData.T0));
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<List<Long>>> runs = new ArrayList<>();
      for (JdbcStorage server : List.of(a, b)) {
        runs.add(
            pool.submit(() -> {
              start.await();
              List<Long> mine = new ArrayList<>();
              for (int round = 0; round < 5; round++) {
                mine.addAll(
                    tx(
                        server,
                        (UnitOfWork u) -> {
                          var pending = u.insights().pendingNotesForPlayers(List.of(player), 100);
                          return u.insights()
                              .claimNotes(
                                  pending.stream()
                                      .map(dev.orderhub.storage.InsightsRepository.Note::id)
                                      .toList(),
                                  TestData.T0 + 1);
                        }));
              }
              return mine;
            }));
      }
      start.countDown();
      List<Long> all = new ArrayList<>();
      for (Future<List<Long>> f : runs) {
        all.addAll(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      assertThat(all).as("jeder Hinweis genau einmal").hasSize(30).doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
    assertThat(tx(a, (UnitOfWork u) -> u.insights().pendingNotesForPlayers(List.of(player), 100)))
        .isEmpty();
  }

  @Test
  void pendingNotesForPlayersOnlyReturnsTheAskedPlayersAndRespectsTheLimit() {
    JdbcStorage a = server("srv-a");
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();
    for (int i = 0; i < 5; i++) {
      final int n = i;
      tx(a, (UnitOfWork u) -> u.insights().addNote(p1, "delivered", "x", "a" + n, TestData.T0));
      tx(a, (UnitOfWork u) -> u.insights().addNote(p2, "delivered", "x", "b" + n, TestData.T0));
      tx(a, (UnitOfWork u) -> u.insights().addNote(p3, "delivered", "x", "c" + n, TestData.T0));
    }
    var both = tx(a, (UnitOfWork u) -> u.insights().pendingNotesForPlayers(List.of(p1, p2), 100));
    assertThat(both).hasSize(10).allMatch(n -> !n.player().equals(p3));
    assertThat(tx(a, (UnitOfWork u) -> u.insights().pendingNotesForPlayers(List.of(p1, p2), 4)))
        .hasSize(4);
    assertThat(tx(a, (UnitOfWork u) -> u.insights().pendingNotesForPlayers(List.of(), 10)))
        .isEmpty();
  }
}
