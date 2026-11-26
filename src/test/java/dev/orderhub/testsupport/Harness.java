package dev.orderhub.testsupport;

import dev.orderhub.config.Expiry;
import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.service.OrderService;
import dev.orderhub.tx.AuditSink;
import dev.orderhub.tx.ClaimEngine;
import dev.orderhub.tx.DeliveryEngine;
import dev.orderhub.tx.PayoutWorker;
import dev.orderhub.tx.RecoveryReport;
import dev.orderhub.tx.RecoveryService;
import dev.orderhub.tx.ReturnEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Verdrahtet Storage, Economy und alle Dienste wie ein Prozessstart. */
public final class Harness {

  /** Sammelt Audit-Ausgaben. */
  public static final class RecordingAudit implements AuditSink {
    public final List<RecoveryReport> reports = Collections.synchronizedList(new ArrayList<>());
    public final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void recoveryReport(RecoveryReport report, long timestamp) {
      reports.add(report);
    }

    @Override
    public void adminWarning(String message) {
      warnings.add(message);
    }
  }

  public static final long START = 1_000_000L;

  public final MutableClock clock = new MutableClock(START);
  public final InMemoryStorage storage = new InMemoryStorage(clock, "test-server");
  public final InMemoryEconomy economy = new InMemoryEconomy();
  public final RecordingAudit audit = new RecordingAudit();
  public final Limits limits;
  public final Fees fees;
  public final Expiry expiry;
  public OrderService orders;
  public DeliveryEngine delivery;
  public PayoutWorker worker;
  public ClaimEngine claims;
  public ReturnEngine returns;
  public RecoveryService recovery;

  public Harness(Fees fees, Limits limits) {
    this.fees = fees;
    this.limits = limits;
    this.expiry = new Expiry(Duration.ofDays(7), true);
    boot();
  }

  public Harness() {
    this(Fees.none(), Limits.defaults());
  }

  /** Erzeugt alle Dienste neu (neuer Prozess, gleiche Daten). */
  public void boot() {
    orders = new OrderService(storage, economy, limits, fees, expiry);
    delivery = new DeliveryEngine(storage, fees);
    worker =
        new PayoutWorker(storage, economy, clock, 3, Duration.ofSeconds(1), Duration.ofSeconds(8));
    claims = new ClaimEngine(storage, audit);
    returns = new ReturnEngine(storage, audit);
    recovery = new RecoveryService(storage, claims, returns, audit, clock, Duration.ZERO);
  }

  /** Simuliert Neustart: Fehlerplaene weg, Daten bleiben, Dienste neu. */
  public void restart() {
    storage.restart();
    economy.clearFaults();
    boot();
  }

  public static UUID player(int n) {
    return new UUID(0L, n);
  }

  public static ItemSnapshot item(String key) {
    return new ItemSnapshot(key, 1, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public OrderService.CreateOrderRequest request(UUID owner, long price, int qty, int min) {
    return new OrderService.CreateOrderRequest(
        UUID.randomUUID(),
        owner,
        item("minecraft:diamond"),
        price,
        qty,
        min,
        null,
        false,
        "ores",
        100,
        false);
  }

  public OrderService.CreateResult create(UUID owner, long price, int qty, int min) {
    return orders.createOrder(request(owner, price, qty, min));
  }

  /** Vollstaendige Lieferung: prepare, confirm, commit. */
  public DeliveryEngine.CommitResult deliver(long orderId, UUID supplier, int qty) {
    var prepared = delivery.prepare(orderId, supplier, qty, UUID.randomUUID().toString());
    if (!prepared.ok()) {
      throw new IllegalStateException("prepare failed: " + prepared.status());
    }
    delivery.confirmItemsRemoved(prepared.journalId());
    return delivery.commit(prepared.journalId());
  }

  /** Arbeitet die Queue vollstaendig ab. */
  public void drainPayouts() {
    for (int i = 0; i < 50; i++) {
      var r = worker.runOnce(100);
      if (r.completed() + r.released() + r.escalated() == 0) {
        return;
      }
      clock.advance(60_000);
    }
  }
}
