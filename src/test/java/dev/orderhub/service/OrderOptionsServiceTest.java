package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderOptions;
import dev.orderhub.testsupport.Harness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Optionen eines Auftrags (Toleranz, Auto-Abholung) werden mit dem Auftrag gespeichert. */
class OrderOptionsServiceTest {

  private static final UUID OWNER = Harness.player(1);

  private static Harness harness() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    return h;
  }

  private static OrderOptions optionsOf(Harness h, long orderId) {
    return h.storage.read(uow -> uow.orders().options(orderId));
  }

  private static OrderService.CreateOrderRequest request(Harness h, OrderOptions options) {
    return new OrderService.CreateOrderRequest(
        UUID.randomUUID(),
        OWNER,
        Harness.item("minecraft:diamond_sword"),
        100,
        5,
        1,
        null,
        false,
        "tools",
        10,
        false,
        OrderService.CreateOrderRequest.FEE_UNCHECKED,
        options);
  }

  @Test
  void optionsAreStoredWithTheOrderInTheSameTransaction() {
    Harness h = harness();
    OrderOptions custom = new OrderOptions(60, true, true);
    var result = h.orders.createOrder(request(h, custom));
    assertThat(result.success()).isTrue();
    assertThat(optionsOf(h, result.orderId())).isEqualTo(custom);
  }

  @Test
  void ordersWithoutOptionsKeepTheExactDefault() {
    Harness h = harness();
    var result = h.orders.createOrder(request(h, OrderOptions.EXACT));
    assertThat(optionsOf(h, result.orderId())).isEqualTo(OrderOptions.EXACT);
  }

  @Test
  void aRepeatedRequestDoesNotChangeTheStoredOptions() {
    Harness h = harness();
    var first = request(h, new OrderOptions(70, false, false));
    var created = h.orders.createOrder(first);
    var replay = h.orders.createOrder(first);
    assertThat(replay.status()).isEqualTo(OrderService.CreateStatus.ALREADY_CREATED);
    assertThat(optionsOf(h, created.orderId())).isEqualTo(new OrderOptions(70, false, false));
    assertThat(h.economy.count(dev.orderhub.testsupport.InMemoryEconomy.Op.WITHDRAW)).isEqualTo(1);
  }

  @Test
  void aFailedCreationLeavesNoOptionsBehind() {
    Harness h = harness();
    h.economy.setBalance(OWNER, 1);
    var result = h.orders.createOrder(request(h, new OrderOptions(10, true, true)));
    assertThat(result.success()).isFalse();
    assertThat(h.storage.snapshot().orders()).isEmpty();
  }
}
