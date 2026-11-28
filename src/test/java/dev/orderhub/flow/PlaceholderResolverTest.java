package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.placeholder.PlaceholderCache;
import dev.orderhub.placeholder.PlaceholderResolver;
import dev.orderhub.service.OrderService;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest extends FlowTestBase {

  private PlaceholderResolver resolver;

  @BeforeEach
  void setUp() {
    // Ein synchroner Lader: der erste Zugriff liefert den Fallback, der zweite den Wert.
    PlaceholderCache cache = new PlaceholderCache(100, 60_000, () -> 1L, Runnable::run);
    resolver = new PlaceholderResolver(services, cache);
  }

  private void order(UUID owner) throws Exception {
    h.economy.setBalance(owner, 1_000_000);
    var result =
        h.orders.createOrder(
            new OrderService.CreateOrderRequest(
                UUID.randomUUID(),
                owner,
                codec.encode(new ItemStack(Material.DIAMOND)),
                100,
                1,
                1,
                null,
                false,
                "ores",
                100,
                false));
    assertThat(result.success()).isTrue();
  }

  @Test
  void marketOpenOrdersCountsActiveOrdersOfEveryone() throws Exception {
    order(UUID.randomUUID());
    order(UUID.randomUUID());

    assertThat(resolver.resolve(null, "market_open_orders")).isEqualTo("0"); // Fallback
    assertThat(resolver.resolve(null, "market_open_orders")).isEqualTo("2");
  }

  @Test
  void claimsWaitingCountsOnlyThePlayersOwnParcels() throws Exception {
    var snapshot = codec.encode(new ItemStack(Material.DIAMOND));
    UUID me = supplier.getUniqueId();
    h.storage
        .transact(uow -> uow.claims().add(me, null, snapshot, 5, UUID.randomUUID(), 1L))
        .join();
    h.storage
        .transact(
            uow -> uow.claims().add(UUID.randomUUID(), null, snapshot, 5, UUID.randomUUID(), 1L))
        .join();

    resolver.resolve(me, "claims_waiting");
    assertThat(resolver.resolve(me, "claims_waiting")).isEqualTo("1");
  }

  @Test
  void aPlayerPlaceholderWithoutAPlayerIsEmptyAndAnUnknownNameIsNull() {
    assertThat(resolver.resolve(null, "claims_waiting")).isEmpty();
    assertThat(resolver.resolve(supplier.getUniqueId(), "does_not_exist")).isNull();
  }

  @Test
  void openOrdersIsPerPlayer() throws Exception {
    UUID me = supplier.getUniqueId();
    order(me);
    order(UUID.randomUUID());

    resolver.resolve(me, "open_orders");
    assertThat(resolver.resolve(me, "open_orders")).isEqualTo("1");
  }
}
