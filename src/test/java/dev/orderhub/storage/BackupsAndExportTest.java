package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupsAndExportTest {

  @TempDir Path dir;

  private JdbcStorage open(String name) throws Exception {
    return JdbcStorage.open(
        DataSourceFactory.sqlite(dir.resolve(name)), Dialect.SQLITE, StorageConfig.defaults("t"));
  }

  private static void addOrder(JdbcStorage storage, String item) {
    storage
        .transact(uow ->
            uow.orders().insert(TestData.order(UUID.randomUUID(), item, 1_000, 5), TestData.T0))
        .join();
  }

  private static long count(javax.sql.DataSource ds, String sql) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void aBackupIsAConsistentCopyWithAllData() throws Exception {
    try (JdbcStorage storage = open("live.db")) {
      addOrder(storage, "minecraft:diamond");
      addOrder(storage, "minecraft:iron_ingot");
      Path backup =
          Backups.sqliteBackup(storage.dataSource(), dir.resolve("backups"), "manual", 10);
      assertThat(backup).exists();
      try (JdbcStorage copy =
          JdbcStorage.open(
              DataSourceFactory.sqlite(backup), Dialect.SQLITE, StorageConfig.defaults("c"))) {
        assertThat(count(copy.dataSource(), "SELECT COUNT(*) FROM orders")).isEqualTo(2);
      }
    }
  }

  @Test
  void oldBackupsAreRemovedBeyondTheLimit() throws Exception {
    try (JdbcStorage storage = open("live.db")) {
      for (int i = 0; i < 5; i++) {
        Backups.sqliteBackup(storage.dataSource(), dir.resolve("b"), "manual", 3);
        Thread.sleep(5);
      }
      try (var files = Files.list(dir.resolve("b"))) {
        assertThat(files.count()).isEqualTo(3);
      }
    }
  }

  @Test
  void aBackupIsOnlyTakenWhenAMigrationIsPendingOnAnExistingDatabase() throws Exception {
    try (JdbcStorage storage = open("live.db")) {
      addOrder(storage, "minecraft:diamond");
      // vollstaendig migriert: keine Sicherung noetig
      assertThat(Backups.beforeMigration(storage.dataSource(), Dialect.SQLITE, dir.resolve("pre")))
          .isEmpty();
      // Zustand einer aelteren Version simulieren: zwei Migrationen fehlen
      try (Connection c = storage.dataSource().getConnection();
          Statement st = c.createStatement()) {
        st.executeUpdate("DELETE FROM schema_version WHERE version >= 4");
      }
      var backup =
          Backups.beforeMigration(storage.dataSource(), Dialect.SQLITE, dir.resolve("pre"));
      assertThat(backup).isPresent();
      assertThat(backup.get().getFileName().toString()).startsWith("pre-migration-v");
    }
  }

  @Test
  void aFreshDatabaseIsNotBackedUp() throws Exception {
    var ds = DataSourceFactory.sqlite(dir.resolve("fresh.db"));
    assertThat(Backups.beforeMigration(ds, Dialect.SQLITE, dir.resolve("pre"))).isEmpty();
    assertThat(dir.resolve("pre")).doesNotExist();
  }

  @Test
  void csvCellsAreQuotedAndProtectedAgainstFormulas() {
    assertThat(CsvExport.cell("plain")).isEqualTo("plain");
    assertThat(CsvExport.cell("a,b")).isEqualTo("\"a,b\"");
    assertThat(CsvExport.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(CsvExport.cell("=1+1")).isEqualTo("'=1+1");
    assertThat(CsvExport.cell("+SUM(A1)")).isEqualTo("'+SUM(A1)");
    assertThat(CsvExport.cell("-2")).isEqualTo("'-2");
    assertThat(CsvExport.cell("@cmd")).isEqualTo("'@cmd");
    assertThat(CsvExport.cell(null)).isEmpty();
  }

  @Test
  void ordersAreExportedWithAHeaderAndOneRowEach() throws Exception {
    try (JdbcStorage storage = open("live.db")) {
      addOrder(storage, "minecraft:diamond");
      addOrder(storage, "minecraft:iron_ingot");
      StringWriter out = new StringWriter();
      long rows = CsvExport.writeOrders(storage, out);
      assertThat(rows).isEqualTo(2);
      String[] lines = out.toString().split("\n");
      assertThat(lines).hasSize(3);
      assertThat(lines[0]).startsWith("id,owner,server,item,unit_price");
      assertThat(out.toString()).contains("minecraft:diamond").contains("minecraft:iron_ingot");
      assertThat(
              storage
                  .transact(uow ->
                      uow.orders()
                          .count(new OrderQuery(null, null, null, OrderStatus.ACTIVE, null, 0, 1)))
                  .join())
          .isEqualTo(2);
    }
  }

  @Test
  void theLedgerIsExportedInInsertionOrder() throws Exception {
    try (JdbcStorage storage = open("live.db")) {
      UUID tx = UUID.randomUUID();
      UUID player = UUID.randomUUID();
      storage
          .transact(uow -> {
            uow.ledger()
                .append(
                    new LedgerRepository.Entry(
                        UUID.randomUUID(),
                        tx,
                        player,
                        LedgerRepository.Kind.ESCROW_RESERVE,
                        500,
                        1,
                        "=evil"));
            uow.ledger()
                .append(
                    new LedgerRepository.Entry(
                        UUID.randomUUID(), tx, player, LedgerRepository.Kind.PAYOUT, 300, 2, "ok"));
            return null;
          })
          .join();
      StringWriter out = new StringWriter();
      assertThat(CsvExport.writeLedger(storage, out)).isEqualTo(2);
      String text = out.toString();
      assertThat(text.indexOf("ESCROW_RESERVE")).isLessThan(text.indexOf("PAYOUT"));
      assertThat(text).contains("'=evil");
    }
  }
}
