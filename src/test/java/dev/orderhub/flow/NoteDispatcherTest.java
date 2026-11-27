package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hinweise von einem anderen Server erreichen den Spieler, waehrend er online ist. */
class NoteDispatcherTest extends FlowTestBase {

  private JdbcStorage insightStorage;
  private InsightsService insights;
  private NoteDispatcher dispatcher;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("notes.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("srv-a"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    Services base = services;
    Services withInsights =
        new Services(
            base.logger(),
            base.storage(),
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
    dispatcher = new NoteDispatcher(withInsights, () -> List.of(supplier));
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private void note(UUID player, String kind, String payload, String key) {
    insightStorage
        .transact(uow -> uow.insights().addNote(player, kind, payload, key, 1_000L))
        .join();
  }

  private String nextMessage() {
    var c = supplier.nextComponentMessage();
    return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
  }

  @Test
  void aDeliveryNoteFromAnotherServerIsShownLiveExactlyOnce() {
    note(supplier.getUniqueId(), "delivered", "minecraft:diamond|12|5", "d1");
    note(supplier.getUniqueId(), "delivered", "minecraft:diamond|8|6", "d2");

    dispatcher.runOnce();
    assertThat(nextMessage()).contains("Just now").contains("20 items").contains("2 deliveries");
    assertThat(nextMessage()).isNull();

    dispatcher.runOnce(); // nichts Neues: keine zweite Anzeige
    assertThat(nextMessage()).isNull();
  }

  @Test
  void notesOfOtherPlayersAreNotShownToThisPlayer() {
    note(UUID.randomUUID(), "delivered", "minecraft:diamond|12|5", "other");
    dispatcher.runOnce();
    assertThat(nextMessage()).isNull();
  }

  @Test
  void aPlayerWhoLeavesRightBeforeTheDisplayCausesNoError() {
    note(supplier.getUniqueId(), "delivered", "minecraft:diamond|1|1", "x");
    supplier.disconnect();
    dispatcher.runOnce();
    assertThat(supplier.isOnline()).isFalse();
  }

  @Test
  void aBrokenDatabaseSkipsTheRunAndKeepsTheNotes() {
    note(supplier.getUniqueId(), "delivered", "minecraft:diamond|1|1", "y");
    insightStorage.close();
    dispatcher.runOnce(); // darf nicht werfen
    assertThat(nextMessage()).isNull();
  }
}
