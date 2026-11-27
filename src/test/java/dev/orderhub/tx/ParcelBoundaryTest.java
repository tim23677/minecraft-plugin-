package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.tx.ParcelTransferEngine.HandoverStatus;
import dev.orderhub.tx.ParcelTransferEngine.Reservation;
import dev.orderhub.tx.ParcelTransferEngine.ReserveStatus;
import dev.orderhub.tx.ParcelTransferEngine.SettleStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Grenzfaelle der Paket-Uebergabe: Zeitgrenze der Freigabe haengender Reservierungen (inklusive),
 * Trennung von Abholung und Rueckgabe, Abrechnungsregeln. Gegen Item-Dupes und verlorene Items.
 */
class ParcelBoundaryTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);

  private static long claimParcel(Harness h) {
    h.economy.setBalance(OWNER, 1_000_000);
    long order = h.create(OWNER, 100, 5, 1).orderId();
    h.deliver(order, SUPPLIER, 5);
    return h.storage.snapshot().claims().get(0).id();
  }

  private static ParcelRepository.Parcel claim(Harness h, long id) {
    return h.storage.read(uow -> uow.claims().find(id).orElseThrow());
  }

  private static JournalEntry journal(Harness h, UUID id) {
    return h.storage.read(uow -> uow.journal().find(id).orElseThrow());
  }

  @Test
  void aReservationExactlyAsOldAsTheLimitIsReleasedAndAYoungerOneIsNot() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    long reservedAt = h.clock.millis();

    var tooYoung = h.claims.releaseHanging(reservedAt - 1);
    assertThat(tooYoung.released()).isZero();
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.COMMITTED);

    var onTheEdge = h.claims.releaseHanging(reservedAt);
    assertThat(onTheEdge.released()).isEqualTo(1);
    assertThat(onTheEdge.escalations()).isEmpty();
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.DELIVERY_STORED);
  }

  @Test
  void aStartedHandoverIsEscalatedExactlyAtTheLimitAndNeverReleased() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    h.claims.beginHandover(r.journalId());
    long startedAt = h.clock.millis();

    var early = h.claims.releaseHanging(startedAt - 1);
    assertThat(early.released()).isZero();
    assertThat(early.escalations()).isEmpty();
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.CLAIM_PENDING);

    var late = h.claims.releaseHanging(startedAt);
    assertThat(late.released()).isZero();
    assertThat(late.escalations()).hasSize(1);
    assertThat(late.escalations().get(0).type()).isEqualTo(TxType.CLAIM);
    assertThat(late.escalations().get(0).amount()).isEqualTo(3);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);

    // ein erneuter Lauf meldet denselben Fall nicht noch einmal
    assertThat(h.claims.releaseHanging(startedAt).escalations()).isEmpty();
  }

  @Test
  void aClaimEngineNeverTouchesJournalsOfTheReturnEngineAndViceVersa() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 2, "a");
    assertThat(h.returns.beginHandover(r.journalId())).isEqualTo(HandoverStatus.NOT_FOUND);
    assertThat(h.returns.settle(r.journalId(), 2)).isEqualTo(SettleStatus.NOT_FOUND);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.COMMITTED);
    assertThat(h.claims.beginHandover(UUID.randomUUID())).isEqualTo(HandoverStatus.NOT_FOUND);
  }

  @Test
  void settlingZeroBeforeAnyHandoverReleasesTheParcelAndAQuantityIsAProtocolBreach() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");

    // Menge ohne begonnene Uebergabe: Protokollbruch, nichts wird abgebucht, Admin wird gewarnt
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.INVALID_STATE);
    assertThat(h.audit.warnings).hasSize(1);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);

    assertThat(h.claims.settle(r.journalId(), 0)).isEqualTo(SettleStatus.RELEASED);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    assertThat(h.claims.settle(r.journalId(), 0)).isEqualTo(SettleStatus.ALREADY_RELEASED);
    assertThat(h.audit.warnings).hasSize(1);
  }

  @Test
  void settleRejectsNegativeAndTooLargeAmountsWithoutChangingAnything() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    h.claims.beginHandover(r.journalId());
    assertThat(h.claims.settle(r.journalId(), -1)).isEqualTo(SettleStatus.INVALID_AMOUNT);
    assertThat(h.claims.settle(r.journalId(), 4)).isEqualTo(SettleStatus.INVALID_AMOUNT);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.CLAIM_PENDING);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    // genau die reservierte Menge ist die Obergrenze (inklusive)
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.SETTLED);
    assertThat(claim(h, parcel).quantity()).isEqualTo(2);
  }

  @Test
  void aParcelWithoutSpaceIsReportedAndNotReserved() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    assertThat(h.claims.reserve(parcel, OWNER, 0, "a").status()).isEqualTo(ReserveStatus.NO_SPACE);
    assertThat(h.claims.reserve(parcel, OWNER, 1, "b").quantity()).isEqualTo(1);
    assertThat(h.claims.reserve(parcel, Harness.player(9), 1, "c").status())
        .isEqualTo(ReserveStatus.NOT_OWNER);
    assertThat(h.claims.reserve(parcel + 1000, OWNER, 1, "d").status())
        .isEqualTo(ReserveStatus.NOT_FOUND);
  }
}
