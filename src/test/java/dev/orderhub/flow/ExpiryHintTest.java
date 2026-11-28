package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.service.OrderService;
import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ExpiryHintTest extends FlowTestBase {

  private String messages() {
    StringBuilder out = new StringBuilder();
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
    }
    return out.toString();
  }

  private long order(Duration lifetime) throws Exception {
    h.economy.setBalance(supplier.getUniqueId(), 10_000_000);
    var result =
        h.orders.createOrder(
            new OrderService.CreateOrderRequest(
                UUID.randomUUID(),
                supplier.getUniqueId(),
                codec.encode(new ItemStack(Material.DIAMOND)),
                100,
                1,
                1,
                lifetime,
                false,
                "ores",
                100,
                false));
    assertThat(result.success()).isTrue();
    return result.orderId();
  }

  private long createdAt(long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow()).createdAt();
  }

  @Test
  void ordersEndingWithinTheWindowAreCountedAndOthersAreNot() throws Exception {
    long soon = order(Duration.ofHours(5));
    order(Duration.ofHours(10));
    order(Duration.ofHours(100));
    long now = createdAt(soon);

    new ExpiryHint(services, () -> now).check(supplier);

    String out = messages();
    assertThat(out).contains("2 of your orders expire within 24 hours");
  }

  @Test
  void noOrdersInTheWindowMeansNoMessage() throws Exception {
    long id = order(Duration.ofHours(100));

    new ExpiryHint(services, () -> createdAt(id)).check(supplier);

    assertThat(messages()).isEmpty();
  }

  @Test
  void anAlreadyExpiredOrderIsNotCounted() throws Exception {
    long id = order(Duration.ofHours(2));

    new ExpiryHint(services, () -> createdAt(id) + 3 * 3_600_000L).check(supplier);

    assertThat(messages()).isEmpty();
  }

  @Test
  void anOrderOfAnotherPlayerIsNotCounted() throws Exception {
    long id = order(Duration.ofHours(2));
    var other = server.addPlayer();

    new ExpiryHint(services, () -> createdAt(id)).check(other);

    assertThat(other.nextComponentMessage()).isNull();
  }
}
