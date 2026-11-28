package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderOptions;
import dev.orderhub.service.OrderService;
import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class InfoFlowTest extends FlowTestBase {

  private String messages() {
    StringBuilder out = new StringBuilder();
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
    }
    return out.toString();
  }

  private long order(OrderOptions options) throws Exception {
    h.economy.setBalance(OWNER, 1_000_000);
    var request =
        new OrderService.CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            codec.encode(new ItemStack(Material.DIAMOND)),
            250,
            8,
            1,
            Duration.ofHours(48),
            false,
            "ores",
            100,
            false,
            OrderService.CreateOrderRequest.FEE_UNCHECKED,
            options);
    var result = h.orders.createOrder(request);
    assertThat(result.success()).isTrue();
    return result.orderId();
  }

  @Test
  void showsQuantityPriceStatusAndRemainingTime() throws Exception {
    long id = order(OrderOptions.EXACT);

    long createdAt = h.storage.read(uow -> uow.orders().find(id).orElseThrow()).createdAt();

    new InfoFlow(services, () -> createdAt + 3_600_000L).show(supplier, id);

    String text = messages();
    assertThat(text).contains("#" + id).contains("0/8x").contains("2.50").contains("active");
    assertThat(text).contains("ends in 47 h").contains("Options: -");
  }

  @Test
  void listsTheOptionsOfTheOrder() throws Exception {
    long id = order(new OrderOptions(60, true, true));

    new InfoFlow(services).show(supplier, id);

    assertThat(messages())
        .contains("durability at least 60%")
        .contains("enchantments at least")
        .contains("auto-claim");
  }

  @Test
  void anUnknownOrderIsReportedAndAnythingElseStaysUntouched() {
    new InfoFlow(services).show(supplier, 4711);

    assertThat(messages()).contains("#4711");
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void anExpiredOrderShowsZeroHoursLeft() throws Exception {
    long id = order(OrderOptions.EXACT);

    new InfoFlow(services, () -> Long.MAX_VALUE / 2).show(supplier, id);

    assertThat(messages()).contains("ends in 0 h");
  }
}
