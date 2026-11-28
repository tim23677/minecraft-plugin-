package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderOptions;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.service.CategoryBackfill;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Upgrade einer echten 1.1.0-Datenbank (erzeugt mit dem 1.1.0-Quellstand, vier Auftraege, eine
 * Lieferung mit Auszahlung, ein Paket, ein Favorit) auf das Schema von 2.0.0.
 */
class UpgradeFrom110Test {

  private static Path copyDatabase(Path dir) throws Exception {
    Path target = dir.resolve("orderhub.db");
    try (InputStream in = UpgradeFrom110Test.class.getResourceAsStream("/orderhub-1.1.0.db")) {
      Files.copy(java.util.Objects.requireNonNull(in), target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  @Test
  void theOldDatabaseIsBackedUpMigratedAndKeepsEveryRow(@TempDir Path dir) throws Exception {
    Path db = copyDatabase(dir);
    DataSource source = DataSourceFactory.sqlite(db);

    // 1. Vor der Migration entsteht eine Sicherung der vorhandenen Daten.
    var backup = Backups.beforeMigration(source, Dialect.SQLITE, dir.resolve("backups"));
    assertThat(backup).isPresent();
    assertThat(Files.size(backup.get())).isPositive();

    // 2. Migration: V5 (gemeinsam und SQLite) kommt dazu, V1 bis V4 bleiben unveraendert.
    int applied = Migrations.apply(source, Dialect.SQLITE);
    assertThat(applied).isEqualTo(2);
    assertThat(Migrations.apply(source, Dialect.SQLITE)).as("zweiter Lauf").isZero();

    try (JdbcStorage storage =
        new JdbcStorage(source, Dialect.SQLITE, StorageConfig.defaults("main"))) {
      // 3. Alle Auftraege sind unveraendert.
      var orders =
          storage
              .transact(uow ->
                  uow.orders()
                      .search(
                          new OrderQuery(
                              null, null, null, OrderStatus.ACTIVE, OrderQuery.Sort.NEWEST, 0, 50)))
              .join();
      assertThat(orders).hasSize(4);
      var first = orders.stream().filter(o -> o.id() == 1).findFirst().orElseThrow();
      assertThat(first.quantityFilled()).isEqualTo(5);
      assertThat(first.quantityTotal()).isEqualTo(20);
      assertThat(first.unitPrice()).isEqualTo(250);
      assertThat(first.escrowRemaining()).isEqualTo(15 * 250L);
      assertThat(first.escrowConsistent()).isTrue();

      // 4. Neue Funktionen laufen auf alten Daten: Optionen fehlen = exakt, Fuellstand-Spalte
      // rechnet.
      assertThat(storage.transact(uow -> uow.orders().options(1)).join())
          .isEqualTo(OrderOptions.EXACT);
      assertThat(first.fillPermille()).isEqualTo(250);
      var almostDone =
          storage
              .transact(uow ->
                  uow.orders()
                      .search(
                          new OrderQuery(
                              null,
                              null,
                              null,
                              OrderStatus.ACTIVE,
                              OrderQuery.Sort.ALMOST_COMPLETED,
                              0,
                              1)))
              .join();
      assertThat(almostDone.get(0).id()).isEqualTo(1);
      var swords =
          storage
              .transact(uow ->
                  uow.orders()
                      .search(
                          new OrderQuery(
                              null, null, null, OrderStatus.ACTIVE, null, 0, 10, "sword", null)))
              .join();
      assertThat(swords)
          .extracting(o -> o.item().itemKey())
          .containsExactly("minecraft:diamond_sword");

      // 5. Die Kategorien von 1.1.0 werden den neuen zugeordnet, genau einmal.
      assertThat(CategoryBackfill.run(storage)).isEqualTo(3);
      assertThat(CategoryBackfill.run(storage)).isZero();
      var byId =
          storage
              .transact(uow -> {
                var map = new java.util.HashMap<Long, String>();
                for (long id = 1; id <= 4; id++) {
                  map.put(id, uow.orders().find(id).orElseThrow().category());
                }
                return map;
              })
              .join();
      assertThat(byId.get(1L)).isEqualTo("ores");
      assertThat(byId.get(2L)).isEqualTo("farm");
      assertThat(byId.get(3L)).isEqualTo("blocks");
      assertThat(byId.get(4L)).isEqualTo("tools");

      // 6. Journal, Auszahlung, Paket und Favorit sind noch da.
      var counts =
          storage
              .transact(uow ->
                  new long[] {
                    uow.journal().findByPlayer(new java.util.UUID(0, 2), 0, 50).size(),
                    uow.claims().listAvailable(new java.util.UUID(0, 1), 0, 50).size(),
                    uow.insights().favorites(new java.util.UUID(0, 1)).size()
                  })
              .join();
      assertThat(counts[0]).isPositive();
      assertThat(counts[1]).isEqualTo(1);
      assertThat(counts[2]).isEqualTo(1);

      // 7. Vorlagen funktionieren auf der migrierten Datenbank.
      var template =
          new dev.orderhub.domain.OrderTemplate(
              0,
              new java.util.UUID(0, 1),
              "my-template",
              new dev.orderhub.domain.ItemSnapshot("minecraft:diamond", 1, new byte[] {1, 2, 3}),
              100,
              64,
              8,
              72,
              new OrderOptions(80, true, true),
              true,
              1L);
      assertThat(storage.transact(uow -> uow.templates().save(template, 10)).join()).isTrue();
    }
  }
}
