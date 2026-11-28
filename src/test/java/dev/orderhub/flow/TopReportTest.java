package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.InsightsRepository.TopStat;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import dev.orderhub.tx.DeliveryCommitted;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopReportTest extends FlowTestBase {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

  private JdbcStorage store;
  private InsightsService insights;

  private Services build(boolean discordOn) {
    DiscordService discord =
        new DiscordService(
            store,
            () ->
                new DiscordService.Config(
                    discordOn, "OrderHub", Set.of(DiscordService.Event.TOP_TRADERS), 1_000_000),
            Logger.getLogger("test"));
    Services b = services;
    return new Services(
        b.logger(),
        store,
        b.scheduler(),
        b.messages(),
        b.settings(),
        b.rates(),
        b.orders(),
        b.delivery(),
        b.claims(),
        b.returns(),
        b.payouts(),
        b.codec(),
        b.matcher(),
        b.menus(),
        insights,
        discord);
  }

  @BeforeEach
  void setUp() throws Exception {
    store =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("report.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    insights =
        new InsightsService(
            store,
            new DiscordService(store, DiscordService.Config::disabled, Logger.getLogger("t")),
            () -> 60,
            () -> 3);
    insights.deliveryCommitted(
        new DeliveryCommitted(
            UUID.randomUUID(),
            1,
            "minecraft:diamond",
            A,
            C,
            4,
            250,
            1_000,
            0,
            1_000,
            true,
            0,
            "t"));
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void theRankingIsQueuedAsADiscordMessageAndAudited() {
    boolean sent =
        new TopReport(build(true), id -> "Alice")
            .publish(TopStat.EARNED, 10, "console", "weekly post");

    assertThat(sent).isTrue();
    var due = store.transact(uow -> uow.outbox().dueDiscord(Long.MAX_VALUE, 10)).join();
    assertThat(due).hasSize(1);
    assertThat(due.get(0).payload())
        .contains("Top players by earned")
        .contains("1. Alice - 10.00")
        .contains("\"allowed_mentions\":{\"parse\":[]}");
    var audit = store.transact(uow -> uow.audit().recent(5)).join();
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).action()).isEqualTo("discord.top");
    assertThat(audit.get(0).reason()).isEqualTo("weekly post");
  }

  @Test
  void nothingIsQueuedWhenDiscordIsOffAndThePlayerNameCannotInjectAMention() {
    assertThat(new TopReport(build(false), id -> "x").publish(TopStat.EARNED, 10, "console", "r"))
        .isFalse();
    assertThat(store.transact(uow -> uow.outbox().dueDiscord(Long.MAX_VALUE, 10)).join()).isEmpty();

    new TopReport(build(true), id -> "@everyone").publish(TopStat.DELIVERED, 10, "console", "r");
    var due = store.transact(uow -> uow.outbox().dueDiscord(Long.MAX_VALUE, 10)).join();
    assertThat(due.get(0).payload()).doesNotContain("@everyone");
  }
}
