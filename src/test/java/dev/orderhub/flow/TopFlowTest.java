package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.placeholder.PlaceholderCache;
import dev.orderhub.placeholder.PlaceholderResolver;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import dev.orderhub.tx.DeliveryCommitted;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rangliste per Befehl und Platzhalter aus echten Statistikzeilen (SQLite). */
class TopFlowTest extends FlowTestBase {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

  private JdbcStorage insightStorage;
  private InsightsService insights;
  private Services withInsights;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("top.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    Services base = services;
    withInsights =
        new Services(
            base.logger(),
            insightStorage,
            base.scheduler(),
            base.messages(),
            base.settings(),
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
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private void delivery(UUID supplier, UUID owner, int qty, long unit) {
    long gross = qty * unit;
    insights.deliveryCommitted(
        new DeliveryCommitted(
            UUID.randomUUID(),
            1,
            "minecraft:diamond",
            supplier,
            owner,
            qty,
            unit,
            gross,
            0,
            gross,
            true,
            0,
            "t"));
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
  void theRankingIsSortedByTheChosenStatAndFormattedAccordingly() {
    delivery(A, C, 10, 100); // A: 10 Items, 10,00 verdient
    delivery(B, C, 3, 1_000); // B: 3 Items, 30,00 verdient
    TopFlow flow =
        new TopFlow(withInsights, id -> id.equals(A) ? "Alice" : id.equals(B) ? "Bob" : "Carol");

    flow.show(supplier, new String[] {"earned"});
    String earned = messages();
    flow.show(supplier, new String[] {"delivered", "5"});
    String delivered = messages();

    assertThat(earned).containsSubsequence("1. Bob - 30.00", "2. Alice - 10.00");
    assertThat(delivered).containsSubsequence("1. Alice - 10", "2. Bob - 3");
    assertThat(earned).contains("earned");
  }

  @Test
  void theCountIsLimitedAndBadWordsShowTheUsage() {
    for (int i = 0; i < 4; i++) {
      delivery(UUID.randomUUID(), C, 1, 100);
    }
    TopFlow flow = new TopFlow(withInsights, id -> "p");

    flow.show(supplier, new String[] {"earned", "2"});
    assertThat(messages().lines().filter(l -> l.matches("\\d+\\. .*")).count()).isEqualTo(2);

    flow.show(supplier, new String[] {"nonsense"});
    assertThat(messages()).contains("/order top");
  }

  @Test
  void anEmptyStatisticSaysSo() {
    new TopFlow(withInsights, id -> "p").show(supplier, new String[0]);
    assertThat(messages()).contains("no ranking");
  }

  @Test
  void placeholdersGiveNameAndValueOfARankAndDashesBeyondTheList() {
    delivery(A, C, 10, 100);
    delivery(B, C, 3, 1_000);
    PlaceholderResolver resolver =
        new PlaceholderResolver(
            withInsights, new PlaceholderCache(100, 60_000, () -> 1L, Runnable::run));

    resolver.resolve(null, "top_earned_1_value");
    resolver.resolve(null, "top_earned_2_value");
    resolver.resolve(null, "top_earned_9_value");
    resolver.resolve(null, "top_delivered_1_value");

    assertThat(resolver.resolve(null, "top_earned_1_value")).isEqualTo("30.00");
    assertThat(resolver.resolve(null, "top_earned_2_value")).isEqualTo("10.00");
    assertThat(resolver.resolve(null, "top_earned_9_value")).isEqualTo("-");
    assertThat(resolver.resolve(null, "top_delivered_1_value")).isEqualTo("10");
    assertThat(resolver.resolve(null, "top_earned_11_value")).isNull();
    assertThat(resolver.resolve(null, "top_nonsense_1_value")).isNull();
    assertThat(resolver.resolve(null, "top_earned_1_color")).isNull();
  }
}
