package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.service.OrderService;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** /order renew: nur der Besitzer, nur on oder off, eine gueltige Auftragsnummer. */
class RenewFlowTest extends FlowTestBase {

  private String messages() {
    StringBuilder out = new StringBuilder();
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
    }
    return out.toString();
  }

  private long order() throws Exception {
    var request =
        new OrderService.CreateOrderRequest(
            UUID.randomUUID(),
            supplier.getUniqueId(),
            codec.encode(new ItemStack(Material.DIAMOND)),
            100,
            5,
            1,
            null,
            false,
            "ores",
            100,
            false);
    h.economy.setBalance(supplier.getUniqueId(), 1_000_000);
    return h.orders.createOrder(request).orderId();
  }

  @Test
  void ownerTurnsItOnAndOff() throws Exception {
    long id = order();
    RenewFlow flow = new RenewFlow(services);
    flow.handle(supplier, new String[] {"renew", String.valueOf(id), "on"});
    assertThat(messages()).contains("renew.on");
    assertThat(h.storage.read(uow -> uow.orders().find(id).orElseThrow()).autoRenew()).isTrue();
    flow.handle(supplier, new String[] {"renew", String.valueOf(id), "off"});
    assertThat(h.storage.read(uow -> uow.orders().find(id).orElseThrow()).autoRenew()).isFalse();
  }

  @Test
  void foreignOrdersAndBadInputChangeNothing() throws Exception {
    long id = order();
    RenewFlow flow = new RenewFlow(services);
    var other = server.addPlayer();
    flow.handle(other, new String[] {"renew", String.valueOf(id), "on"});
    assertThat(h.storage.read(uow -> uow.orders().find(id).orElseThrow()).autoRenew()).isFalse();
    flow.handle(supplier, new String[] {"renew", "abc", "on"});
    flow.handle(supplier, new String[] {"renew", String.valueOf(id)});
    flow.handle(supplier, new String[] {"renew", String.valueOf(id), "maybe"});
    assertThat(h.storage.read(uow -> uow.orders().find(id).orElseThrow()).autoRenew()).isFalse();
    assertThat(messages()).contains("renew");
  }
}
