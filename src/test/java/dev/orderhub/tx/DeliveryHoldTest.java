package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.storage.HoldRepository;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.tx.DeliveryEngine.PrepareRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invariante: Items vorher (im Menue) = Items nachher (Claim + Rueckgabe + Reservierung). */
class DeliveryHoldTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final int IN_MENU = 7;
  private static final int DELIVERED = 4;

  private static Harness harness() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 1_000_000);
    return h;
  }

  private static UUID prepare(Harness h, long orderId) {
    var p =
        h.delivery.prepare(
            new PrepareRequest(
                orderId, SUPPLIER, DELIVERED, UUID.randomUUID().toString(), null, false, IN_MENU));
    assertThat(p.ok()).isTrue();
    return p.journalId();
  }

  private static int accounted(Harness h, UUID tx) {
    int total = 0;
    var snap = h.storage.snapshot();
    for (ParcelRepository.Parcel p : snap.claims()) {
      if (tx.equals(p.sourceTx())) {
        total += p.quantity();
      }
    }
    for (ParcelRepository.Parcel p : snap.returns()) {
      if (tx.equals(p.sourceTx())) {
        total += p.quantity();
      }
    }
    for (var hold : h.storage.holds()) {
      if (tx.equals(hold.txId()) && hold.state() == HoldRepository.State.HELD) {
        total += hold.quantity();
      }
    }
    return total;
  }

  private static int returned(Harness h, UUID tx) {
    return h.storage.snapshot().returns().stream()
        .filter(p -> tx.equals(p.sourceTx()))
        .mapToInt(ParcelRepository.Parcel::quantity)
        .sum();
  }

  private static HoldRepository.State holdState(Harness h, UUID tx) {
    return h.storage.holds().stream()
        .filter(x -> x.txId().equals(tx))
        .findFirst()
        .orElseThrow()
        .state();
  }

  @Test
  void prepareCreatesAHoldForEverythingInTheMenu() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    assertThat(h.storage.holds())
        .singleElement()
        .satisfies(x -> {
          assertThat(x.quantity()).isEqualTo(IN_MENU);
          assertThat(x.state()).isEqualTo(HoldRepository.State.HELD);
        });
    assertThat(accounted(h, tx)).isEqualTo(IN_MENU);
  }

  @ParameterizedTest
  @ValueSource(strings = {"after-prepare", "after-confirm", "after-commit"})
  void crashAtAnyStateNeitherLosesNorDuplicatesItems(String crashPoint) {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    if (!crashPoint.equals("after-prepare")) {
      h.delivery.confirmItemsRemoved(tx);
    }
    if (crashPoint.equals("after-commit")) {
      h.delivery.commit(tx);
    }
    assertThat(accounted(h, tx)).isEqualTo(IN_MENU);

    h.restart(); // Absturz: das Menue ist weg, nur die Datenbank bleibt
    h.recovery.sweep();
    h.recovery.sweep(); // Wiederholung darf nichts veraendern

    if (crashPoint.equals("after-commit")) {
      assertThat(
              h.storage.snapshot().claims().stream()
                  .mapToInt(ParcelRepository.Parcel::quantity)
                  .sum())
          .isEqualTo(DELIVERED);
      assertThat(returned(h, tx)).isEqualTo(IN_MENU - DELIVERED);
    } else {
      assertThat(returned(h, tx)).isEqualTo(IN_MENU);
      assertThat(h.storage.snapshot().claims()).isEmpty();
    }
    assertThat(accounted(h, tx)).isEqualTo(IN_MENU);
    assertThat(h.storage.holds()).noneMatch(x -> x.state() == HoldRepository.State.HELD);
  }

  @Test
  void commitTurnsTheSurplusIntoAReturn() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    h.delivery.confirmItemsRemoved(tx);
    assertThat(h.delivery.commit(tx).status()).isEqualTo(DeliveryEngine.CommitStatus.COMMITTED);
    assertThat(returned(h, tx)).isEqualTo(IN_MENU - DELIVERED);
    assertThat(holdState(h, tx)).isEqualTo(HoldRepository.State.CONSUMED);
    // zweiter Commit aendert nichts
    h.delivery.commit(tx);
    assertThat(accounted(h, tx)).isEqualTo(IN_MENU);
  }

  @Test
  void rejectedCommitReturnsEverythingIncludingTheSurplus() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    h.delivery.confirmItemsRemoved(tx);
    h.orders.cancelOrder(order, OWNER, false);
    assertThat(h.delivery.commit(tx).status())
        .isEqualTo(DeliveryEngine.CommitStatus.REJECTED_ITEMS_RETURNED);
    assertThat(returned(h, tx)).isEqualTo(IN_MENU);
    assertThat(holdState(h, tx)).isEqualTo(HoldRepository.State.RELEASED);
  }

  @Test
  void abortBeforeItemsRemovedLeavesTheItemsToTheCaller() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    assertThat(h.delivery.abort(tx)).isEqualTo(DeliveryEngine.AbortResult.ABORTED);
    assertThat(holdState(h, tx)).isEqualTo(HoldRepository.State.CONSUMED);
    h.restart();
    h.recovery.sweep();
    assertThat(h.storage.snapshot().returns()).isEmpty(); // kein Dupe: Aufrufer gab zurueck
  }

  @Test
  void lookupFindsAPreparedAttempt() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    h.delivery.prepare(new PrepareRequest(order, SUPPLIER, 1, "n1", null, false, 1));
    assertThat(h.delivery.lookup(order, SUPPLIER, "n1")).isPresent();
    assertThat(h.delivery.lookup(order, SUPPLIER, "other")).isEmpty();
  }

  @Test
  void closingAnUnknownAttemptTombstonesTheKeySoALatePrepareCreatesNoHold() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    assertThat(h.delivery.closeAttempt(order, SUPPLIER, "late"))
        .isEqualTo(DeliveryEngine.AttemptStatus.NEVER_PREPARED);
    // verspaetet committendes prepare mit demselben Schluessel: kein Eintrag, keine Reservierung
    var late = h.delivery.prepare(new PrepareRequest(order, SUPPLIER, 2, "late", null, false, 5));
    assertThat(late.status()).isEqualTo(DeliveryEngine.PrepareStatus.KEY_ALREADY_FAILED);
    assertThat(h.storage.holds()).isEmpty();
    // Wiederholung des Abschlusses aendert nichts
    assertThat(h.delivery.closeAttempt(order, SUPPLIER, "late"))
        .isEqualTo(DeliveryEngine.AttemptStatus.EXISTS);
  }

  @Test
  void closingAnAttemptThatWasPreparedLeavesTheItemsToTheReservation() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    h.delivery.prepare(new PrepareRequest(order, SUPPLIER, 2, "n", null, false, 5));
    assertThat(h.delivery.closeAttempt(order, SUPPLIER, "n"))
        .isEqualTo(DeliveryEngine.AttemptStatus.EXISTS);
    h.restart();
    h.recovery.sweep();
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(p -> assertThat(p.quantity()).isEqualTo(5));
  }

  @Test
  void abortAfterRecoveryAlreadyReleasedTheItemsIsNotAnAbortTheCallerMayCompleteItself() {
    Harness h = harness();
    long order = h.create(OWNER, 100, 10, 1).orderId();
    UUID tx = prepare(h, order);
    h.restart();
    h.recovery.sweep(); // Recovery war schneller: Items liegen als Rueckgabe in der Datenbank
    assertThat(h.delivery.abort(tx)).isEqualTo(DeliveryEngine.AbortResult.ALREADY_FAILED);
    assertThat(h.delivery.confirmItemsRemoved(tx)).isEqualTo(DeliveryEngine.ConfirmResult.REJECTED);
    assertThat(returned(h, tx)).isEqualTo(IN_MENU); // genau einmal
  }
}
