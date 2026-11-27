package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.gui.ConfirmMenu;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.testsupport.InMemoryStorage;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Erstellung eines Auftrags ueber das Bestaetigungsmenue: Geld und Auftraege muessen stimmen. */
class CreateFlowTest extends FlowTestBase {

  private static final long START = 1_000_000;
  private static final long ESCROW = 50_000; // 5 Stueck zu je 100,00 = 10.000 Minor Units

  private UUID creator;
  private CreateFlow flow;

  @BeforeEach
  void setUp() {
    creator = supplier.getUniqueId();
    h.economy.setBalance(creator, START);
    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 1));
    ChatInput chat = new ChatInput(scheduler, () -> "cancel", nanos::incrementAndGet);
    flow = new CreateFlow(services, chat);
  }

  private long orders() {
    return h.storage.snapshot().orders().size();
  }

  private long escrowJournals() {
    return h.storage.snapshot().journal().stream()
        .filter(j -> j.type() == TxType.ESCROW_CREATE)
        .count();
  }

  @Test
  void confirmingCreatesTheOrderAndDebitsExactlyOnce() {
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(orders()).isEqualTo(1);
    assertThat(h.economy.balance(creator)).isEqualTo(START - ESCROW);
    assertThat(h.economy.count(Op.WITHDRAW)).isEqualTo(1);
  }

  @Test
  void cancellingChargesNothing() {
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CANCEL_SLOT);

    assertThat(orders()).isZero();
    assertThat(h.economy.balance(creator)).isEqualTo(START);
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void closingTheMenuChargesNothing() {
    flow.start(supplier, "5", "100");
    supplier.closeInventory();

    assertThat(orders()).isZero();
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void tooLittleMoneyCreatesNothingAndChargesNothing() {
    h.economy.setBalance(creator, 100);
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(orders()).isZero();
    assertThat(h.economy.balance(creator)).isEqualTo(100);
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void aDoubleClickCreatesOneOrder() {
    flow.start(supplier, "5", "100");
    var view = supplier.getOpenInventory();
    scheduler.deferAsync = true;
    click(view, ConfirmMenu.CONFIRM_SLOT);
    click(view, ConfirmMenu.CONFIRM_SLOT);
    click(view, ConfirmMenu.CONFIRM_SLOT);
    scheduler.drain();

    assertThat(orders()).isEqualTo(1);
    assertThat(h.economy.count(Op.WITHDRAW)).isEqualTo(1);
    assertThat(h.economy.balance(creator)).isEqualTo(START - ESCROW);
  }

  @Test
  void anUnclearWithdrawalGoesToManualReviewAndIsNeverRetried() {
    h.economy.failNext(Op.WITHDRAW, Mode.THROW_AFTER); // abgebucht, aber Ausnahme
    flow.start(supplier, "5", "100");
    var view = supplier.getOpenInventory();
    click(view, ConfirmMenu.CONFIRM_SLOT);

    assertThat(h.economy.count(Op.WITHDRAW)).isEqualTo(1);
    assertThat(h.storage.snapshot().journal())
        .anyMatch(j -> j.type() == TxType.ESCROW_CREATE && j.state() == TxState.MANUAL_REVIEW);
    // ein weiterer Klick auf die geschlossene Ansicht bucht nicht noch einmal ab
    click(view, ConfirmMenu.CONFIRM_SLOT);
    h.recovery.sweep();
    assertThat(h.economy.count(Op.WITHDRAW)).isEqualTo(1);
  }

  @Test
  void aDatabaseOutageBeforeAnythingHappenedChargesNothing() {
    h.storage.setOutage(true);
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);
    h.storage.setOutage(false);

    assertThat(h.economy.mutationCount()).isZero();
    assertThat(orders()).isZero();
  }

  @Test
  void aLostCommitAcknowledgeNeverChargesTwice() {
    h.storage.failTransaction(1, InMemoryStorage.FailAt.AFTER_COMMIT);
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(h.economy.count(Op.WITHDRAW)).isLessThanOrEqualTo(1);
    assertThat(escrowJournals()).isLessThanOrEqualTo(1);
  }

  @Test
  void theSecondCreationWithinTheCooldownIsRefused() {
    flow.start(supplier, "1", "100");
    click(ConfirmMenu.CONFIRM_SLOT);
    assertThat(orders()).isEqualTo(1);

    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 1));
    flow.start(supplier, "1", "100");
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(orders()).isEqualTo(1); // Standard: 5 Sekunden Abstand
    assertThat(h.economy.balance(creator)).isEqualTo(START - 10_000);
  }

  @Test
  void invalidInputOpensNoMenuAndChargesNothing() {
    flow.start(supplier, "abc", "100");
    flow.start(supplier, "5", "x");
    assertThat(menus.openCount()).isZero();
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void theCooldownEntryIsRemovedWhenThePlayerQuits() {
    flow.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);
    assertThat(flow.cooldownSize()).isEqualTo(1);

    new CooldownListener(flow)
        .onQuit(
            new org.bukkit.event.player.PlayerQuitEvent(
                supplier,
                net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

    assertThat(flow.cooldownSize()).isZero();
  }

  @Test
  void expiredCooldownEntriesArePurgedOnATimerAndActiveOnesStay() {
    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
    ChatInput chat = new ChatInput(scheduler, () -> "cancel", nanos::incrementAndGet);
    CreateFlow timed = new CreateFlow(services, chat, clock::get);
    timed.start(supplier, "5", "100");
    click(ConfirmMenu.CONFIRM_SLOT);
    assertThat(timed.cooldownSize()).isEqualTo(1);

    assertThat(timed.purgeExpiredCooldowns()).isZero(); // Fenster laeuft noch
    clock.addAndGet(60_000);
    assertThat(timed.purgeExpiredCooldowns()).isEqualTo(1);
    assertThat(timed.cooldownSize()).isZero();
  }
}
