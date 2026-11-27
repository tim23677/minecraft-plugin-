package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InterceptingStorage;
import dev.orderhub.tx.ParcelTransferEngine.HandoverStatus;
import dev.orderhub.tx.ParcelTransferEngine.Reservation;
import dev.orderhub.tx.ParcelTransferEngine.ReserveStatus;
import dev.orderhub.tx.ParcelTransferEngine.SettleStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParcelEngineTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long MONEY = 1_000_000;

  /** Ein Claim-Paket mit 5 Items fuer OWNER. */
  private static long claimParcel(Harness h) {
    h.economy.setBalance(OWNER, MONEY);
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
  void claimInPartsWithHandoverMarker() {
    Harness h = new Harness();
    long parcel = claimParcel(h);

    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    assertThat(r.status()).isEqualTo(ReserveStatus.RESERVED);
    assertThat(r.quantity()).isEqualTo(3);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.COMMITTED);

    assertThat(h.claims.beginHandover(r.journalId())).isEqualTo(HandoverStatus.STARTED);
    assertThat(h.claims.beginHandover(r.journalId())).isEqualTo(HandoverStatus.ALREADY_STARTED);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.CLAIM_PENDING);

    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.SETTLED);
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.ALREADY_SETTLED);
    assertThat(claim(h, parcel).quantity()).isEqualTo(2);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);

    Reservation rest = h.claims.reserve(parcel, OWNER, 64, "b");
    assertThat(rest.quantity()).isEqualTo(2);
    h.claims.beginHandover(rest.journalId());
    assertThat(h.claims.settle(rest.journalId(), 2)).isEqualTo(SettleStatus.SETTLED);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.CLAIMED);
    assertThat(claim(h, parcel).quantity()).isZero();
  }

  @Test
  void partialTransferKeepsTheRestAvailable() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 5, "a");
    h.claims.beginHandover(r.journalId());
    assertThat(h.claims.settle(r.journalId(), 2)).isEqualTo(SettleStatus.SETTLED); // nur 2 passten
    assertThat(claim(h, parcel).quantity()).isEqualTo(3);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);
  }

  @Test
  void reserveChecksOwnerAvailabilityAndSpaceAndIsIdempotent() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    assertThat(h.claims.reserve(parcel, SUPPLIER, 5, "x").status())
        .isEqualTo(ReserveStatus.NOT_OWNER);
    assertThat(h.claims.reserve(999, OWNER, 5, "x").status()).isEqualTo(ReserveStatus.NOT_FOUND);
    assertThat(h.claims.reserve(parcel, OWNER, 0, "x").status()).isEqualTo(ReserveStatus.NO_SPACE);
    Reservation first = h.claims.reserve(parcel, OWNER, 2, "same");
    Reservation again = h.claims.reserve(parcel, OWNER, 2, "same");
    assertThat(again.status()).isEqualTo(ReserveStatus.RESERVED);
    assertThat(again.journalId()).isEqualTo(first.journalId());
    assertThat(h.claims.reserve(parcel, OWNER, 2, "other").status())
        .isEqualTo(ReserveStatus.NOT_AVAILABLE);
  }

  @Test
  void settleValidatesAmountAndProtocol() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    // ohne beginHandover darf keine Menge gemeldet werden
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.INVALID_STATE);
    assertThat(h.audit.warnings).isNotEmpty();
    h.claims.beginHandover(r.journalId());
    assertThat(h.claims.settle(r.journalId(), 4)).isEqualTo(SettleStatus.INVALID_AMOUNT);
    assertThat(h.claims.settle(r.journalId(), -1)).isEqualTo(SettleStatus.INVALID_AMOUNT);
    assertThat(h.claims.settle(r.journalId(), 0)).isEqualTo(SettleStatus.RELEASED);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    assertThat(h.claims.settle(r.journalId(), 0)).isEqualTo(SettleStatus.ALREADY_RELEASED);
    assertThat(h.claims.beginHandover(r.journalId())).isEqualTo(HandoverStatus.NOT_ALLOWED);
    assertThat(h.claims.settle(UUID.randomUUID(), 1)).isEqualTo(SettleStatus.NOT_FOUND);
  }

  @Test
  void crashBeforeHandoverIsReleasedByRecovery() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r =
        h.claims.reserve(parcel, OWNER, 3, "a"); // Absturz, bevor Items uebergeben wurden
    h.clock.advance(60_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.claimsReleased()).isEqualTo(1);
    assertThat(report.claimsEscalated()).isZero();
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.AVAILABLE);
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.DELIVERY_STORED);
    // erneut abholbar, genau einmal
    Reservation again = h.claims.reserve(parcel, OWNER, 5, "b");
    assertThat(again.reserved()).isTrue();

    // eine verspaetete Meldung zur freigegebenen Reservierung ist ein protokollierter Fehler
    h.audit.warnings.clear();
    assertThat(h.claims.beginHandover(r.journalId())).isEqualTo(HandoverStatus.NOT_ALLOWED);
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.CONFLICT_RELEASED);
    assertThat(h.audit.warnings).anyMatch(w -> w.contains("CONFLICT_RELEASED"));
    assertThat(claim(h, parcel).quantity()).isEqualTo(5); // nichts abgebucht
  }

  @Test
  void crashAfterHandoverIsNeverReleasedAutomatically() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    assertThat(h.claims.beginHandover(r.journalId())).isEqualTo(HandoverStatus.STARTED);
    // Absturz nach der Uebergabe ins Inventar, vor settle()
    h.clock.advance(60_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.claimsReleased()).isZero();
    assertThat(report.claimsEscalated()).isEqualTo(1);
    assertThat(report.escalations())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.journalId()).isEqualTo(r.journalId());
          assertThat(e.player()).isEqualTo(OWNER);
          assertThat(e.reason()).contains("outcome unknown");
        });
    assertThat(h.audit.warnings).isNotEmpty();
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.MANUAL_REVIEW);

    // nicht erneut abholbar: kein Dupe
    assertThat(h.claims.reserve(parcel, OWNER, 5, "b").status())
        .isEqualTo(ReserveStatus.NOT_AVAILABLE);
    // zweiter Lauf eskaliert nicht erneut
    RecoveryReport second = h.recovery.sweep();
    assertThat(second.claimsEscalated()).isZero();
    assertThat(second.existingManualReview()).isEqualTo(1);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);

    // eine spaete Uebergabemeldung wird als Fehler gemeldet und veraendert nichts
    h.audit.warnings.clear();
    assertThat(h.claims.settle(r.journalId(), 3)).isEqualTo(SettleStatus.CONFLICT_UNDER_REVIEW);
    assertThat(h.audit.warnings).anyMatch(w -> w.contains("CONFLICT_UNDER_REVIEW"));
    assertThat(claim(h, parcel).quantity()).isEqualTo(5);
  }

  @Test
  void freshReservationsOfOtherServersAreNotTouched() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a");
    h.claims.beginHandover(r.journalId());
    RecoveryService careful =
        new RecoveryService(
            h.storage, h.claims, h.returns, h.audit, h.clock, Duration.ofMinutes(10));
    h.clock.advance(1_000);
    RecoveryReport report = careful.runAtStartup();
    assertThat(report.claimsEscalated()).isZero();
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.CLAIM_PENDING);
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);
  }

  @Test
  void entryRefreshedBetweenListingAndProcessingIsLeftAlone() {
    Harness h = new Harness();
    long parcel = claimParcel(h);
    Reservation r = h.claims.reserve(parcel, OWNER, 3, "a"); // COMMITTED, ohne Marker
    h.clock.advance(60_000);
    // Aufrufe der Engine: 1 findReserved, 2 Journal lesen, 3 Freigabe-Transaktion
    InterceptingStorage spy =
        new InterceptingStorage(
            h.storage,
            3,
            () -> {
              // ein anderer Server reserviert das Paket neu: Zeitstempel ist frisch
              dev.orderhub.tx.TxRunner.run(
                  h.storage,
                  uow -> {
                    uow.claims().releaseReservation(parcel, r.journalId(), uow.now());
                    return uow.claims().reserve(parcel, r.journalId(), uow.now());
                  });
            });
    ClaimEngine engine = new ClaimEngine(spy, h.audit);
    var result = engine.releaseHanging(h.clock.millis() - 1_000);
    assertThat(spy.fired()).isTrue();
    assertThat(result.released()).isZero();
    assertThat(claim(h, parcel).state()).isEqualTo(ParcelRepository.State.RESERVED);
    assertThat(journal(h, r.journalId()).state()).isEqualTo(TxState.COMMITTED);
  }

  @Test
  void returnsFollowTheSameRules() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, MONEY);
    long order = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(order, SUPPLIER, 3, "n");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.delivery.abort(p.journalId());
    long parcel = h.storage.snapshot().returns().get(0).id();

    Reservation ok = h.returns.reserve(parcel, SUPPLIER, 3, "a");
    assertThat(ok.reserved()).isTrue();
    h.returns.beginHandover(ok.journalId());
    assertThat(h.returns.settle(ok.journalId(), 3)).isEqualTo(SettleStatus.SETTLED);

    // zweite Rueckgabe: Absturz nach Uebergabe -> MANUAL_REVIEW
    var p2 = h.delivery.prepare(order, SUPPLIER, 2, "n2");
    h.delivery.confirmItemsRemoved(p2.journalId());
    h.delivery.abort(p2.journalId());
    long parcel2 =
        h.storage.snapshot().returns().stream()
            .filter(x -> x.state() == ParcelRepository.State.AVAILABLE)
            .findFirst()
            .orElseThrow()
            .id();
    Reservation r2 = h.returns.reserve(parcel2, SUPPLIER, 2, "b");
    h.returns.beginHandover(r2.journalId());
    h.clock.advance(60_000);
    h.restart();
    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.returnsEscalated()).isEqualTo(1);
    assertThat(report.returnsReleased()).isZero();
  }
}
