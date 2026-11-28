package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminToolsTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);

  @TempDir Path dir;

  private static AdminService admin(Harness h) {
    return new AdminService(h.storage, h.orders, h.recovery);
  }

  @Test
  void healthReportsOpenPaymentsManualReviewAndTheLastRecovery() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    long id = h.create(OWNER, 100, 10, 1).orderId();
    h.deliver(id, SUPPLIER, 1);
    var before = admin(h).health();
    assertThat(before.openPayments()).isEqualTo(1);
    assertThat(before.manualReview()).isZero();
    assertThat(before.lastRecovery()).isNull();

    h.economy.failNext(Op.DEPOSIT, Mode.THROW_AFTER);
    h.worker.runOnce(10);
    h.recovery.sweep();
    var after = admin(h).health();
    assertThat(after.manualReview()).isEqualTo(1);
    assertThat(after.openPayments()).isZero();
    assertThat(after.lastRecovery()).isNotNull();
    assertThat(after.lastRecoveryAt()).isPositive();
  }

  @Test
  void exportWritesACsvFileAndAuditsIt() throws Exception {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    h.create(OWNER, 100, 10, 1);
    var result = admin(h).export("orders", dir, "console", "monthly report");
    assertThat(result.rows()).isEqualTo(1);
    assertThat(result.file().getParent()).isEqualTo(dir.resolve("exports"));
    assertThat(Files.readString(result.file())).startsWith("id,owner,server,item");
    var ledger = admin(h).export("ledger", dir, "console", "monthly report");
    assertThat(ledger.rows()).isPositive();
    var audit = h.storage.read(uow -> uow.audit().recent(10));
    assertThat(audit).extracting(a -> a.action()).contains("export.orders", "export.ledger");
  }

  @Test
  void exportRejectsUnknownKindsAndMissingReasons() {
    Harness h = new Harness();
    assertThatThrownBy(() -> admin(h).export("passwords", dir, "a", "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> admin(h).export("orders", dir, "a", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backupIsRefusedWithoutSqlite() {
    Harness h = new Harness();
    assertThatThrownBy(() -> admin(h).backup(dir, "a", "r"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SQLite");
  }

  @Test
  void playerOverviewCountsOrdersAndParcelsWithoutLocking() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    h.create(OWNER, 100, 10, 1);
    h.create(OWNER, 100, 10, 1);

    var overview = admin(h).playerOverview(OWNER);

    assertThat(overview.player()).isEqualTo(OWNER);
    assertThat(overview.openOrders()).isEqualTo(2);
    assertThat(overview.claimsWaiting()).isZero();
    assertThat(overview.returnsWaiting()).isZero();
    assertThat(overview.moneyEarned()).isZero();
    assertThat(admin(h).playerOverview(SUPPLIER).openOrders()).isZero();
  }

  @Test
  void recentAuditListsTheNewestEntryFirst() {
    Harness h = new Harness();
    AdminService admin = admin(h);
    admin.auditReload("console", "first");
    admin.auditReload("console", "second");

    var recent = admin.recentAudit(10);

    assertThat(recent).hasSizeGreaterThanOrEqualTo(2);
    assertThat(recent.get(0).detail()).isEqualTo("second");
    assertThat(admin.recentAudit(1)).hasSize(1);
  }
}
