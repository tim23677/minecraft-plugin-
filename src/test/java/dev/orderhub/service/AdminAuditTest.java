package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.domain.OrderStatus;
import dev.orderhub.storage.AuditRepository;
import dev.orderhub.testsupport.Harness;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAuditTest {

  private static final UUID OWNER = Harness.player(1);

  private static Harness harness() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    return h;
  }

  private static AdminService admin(Harness h) {
    return new AdminService(h.storage, h.orders, h.recovery);
  }

  private static List<AuditRepository.Entry> audit(Harness h) {
    return h.storage.read(uow -> uow.audit().recent(50));
  }

  @Test
  void cancelAndItsAuditEntryAreOneTransaction() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var result = admin(h).cancel(id, "admin", "spam");
    assertThat(result.ok()).isTrue();
    assertThat(audit(h))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.action()).isEqualTo("order.cancel");
          assertThat(e.target()).isEqualTo(String.valueOf(id));
          assertThat(e.reason()).isEqualTo("spam");
          assertThat(e.actor()).isEqualTo("admin");
        });
  }

  @Test
  void aFailingAuditRollsTheCancellationBack() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    assertThatThrownBy(() ->
            h.orders.cancelOrder(
                id,
                AdminService.SYSTEM,
                true,
                (uow, r) -> {
                  throw new IllegalStateException("audit failed");
                }))
        .isInstanceOf(RuntimeException.class);
    assertThat(h.storage.read(uow -> uow.orders().find(id).orElseThrow()).status())
        .isEqualTo(OrderStatus.ACTIVE);
    assertThat(h.storage.snapshot().payouts()).isEmpty();
  }

  @Test
  void purgeAuditsEveryExpiredOrderInTheSameTransaction() {
    Harness h = harness();
    long a = h.create(OWNER, 100, 5, 1).orderId();
    long b = h.create(OWNER, 100, 5, 1).orderId();
    h.clock.advance(Duration.ofDays(30).toMillis());
    var report = admin(h).purgeExpired("admin", "cleanup");
    assertThat(report.expired()).isEqualTo(2);
    assertThat(audit(h))
        .extracting(AuditRepository.Entry::target)
        .containsExactlyInAnyOrder(String.valueOf(a), String.valueOf(b));
    assertThat(audit(h)).allMatch(e -> e.action().equals("order.purge-expired"));
  }

  @Test
  void reloadIsAudited() {
    Harness h = harness();
    admin(h).auditReload("console", "reloaded by console");
    assertThat(audit(h))
        .singleElement()
        .satisfies(e -> assertThat(e.action()).isEqualTo("config.reload"));
  }
}
