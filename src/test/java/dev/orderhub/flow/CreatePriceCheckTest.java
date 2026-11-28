package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.bootstrap.Settings;
import dev.orderhub.discord.DiscordService;
import dev.orderhub.gui.ConfirmMenu;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Preisplausibilitaet beim Erstellen, mit echter Handelshistorie in SQLite. */
class CreatePriceCheckTest extends FlowTestBase {

  private JdbcStorage insightStorage;
  private CreateFlow flow;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("check.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    InsightsService insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    YamlConfiguration yaml = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    yaml.set("anti-exploit.price-deviation-factor", 5);
    Settings strict = Settings.from(yaml);
    Services base = services;
    Services withCheck =
        new Services(
            base.logger(),
            base.storage(),
            base.scheduler(),
            base.messages(),
            () -> strict,
            base.rates(),
            base.orders(),
            base.delivery(),
            base.claims(),
            base.returns(),
            base.payouts(),
            base.codec(),
            base.matcher(),
            base.menus(),
            insights,
            base.discord());
    ChatInput chat = new ChatInput(scheduler, () -> "cancel", nanos::incrementAndGet);
    flow = new CreateFlow(withCheck, chat);
    h.economy.setBalance(supplier.getUniqueId(), 100_000_000);
    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 1));
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private void trades(int n, long price) {
    long now = System.currentTimeMillis();
    for (int i = 0; i < n; i++) {
      insightStorage
          .transact(uow ->
              uow.insights().recordTrade(UUID.randomUUID(), 1, "minecraft:diamond", price, 1, now))
          .join();
    }
  }

  private String messages() {
    StringBuilder out = new StringBuilder();
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
    }
    return out.toString();
  }

  @Test
  void aPriceFarAboveTheMedianIsRefusedWithoutAMenu() {
    trades(6, 1_000); // Median 10,00

    flow.start(supplier, "1", "100"); // 100,00 ist das Zehnfache

    assertThat(messages()).contains("more than 5 times above").contains("10.00");
    assertThat(menus.openCount()).isZero();
  }

  @Test
  void aPriceFarBelowTheMedianIsRefused() {
    trades(6, 1_000);

    flow.start(supplier, "1", "1"); // 1,00 ist ein Zehntel

    assertThat(messages()).contains("less than 1/5");
    assertThat(menus.openCount()).isZero();
  }

  @Test
  void aReasonablePriceOpensTheConfirmMenu() {
    trades(6, 1_000);

    flow.start(supplier, "1", "20");
    assertThat(menus.openCount()).isEqualTo(1);
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(h.storage.snapshot().orders()).hasSize(1);
  }

  @Test
  void withTooFewTradesThereIsNoReferenceAndNothingIsBlocked() {
    trades(2, 1_000);

    flow.start(supplier, "1", "1000");

    assertThat(menus.openCount()).isEqualTo(1);
  }

  @Test
  void thePermissionSkipsTheCheck() {
    trades(6, 1_000);
    supplier.addAttachment(plugin, "orderhub.bypass.price-check", true);

    flow.start(supplier, "1", "1000");

    assertThat(menus.openCount()).isEqualTo(1);
  }

  @Test
  void aDatabaseOutageDuringTheCheckStopsTheCreation() {
    insightStorage.close();

    flow.start(supplier, "1", "20");

    assertThat(messages()).contains("database error");
    assertThat(menus.openCount()).isZero();
  }
}
