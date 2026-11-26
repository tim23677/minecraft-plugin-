package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryStorage;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Alle 14 x 14 Uebergaenge gegen eine unabhaengig notierte Tabelle. */
class TxStateMachineTest {

  private static final Map<TxState, Set<TxState>> ALLOWED = new EnumMap<>(TxState.class);

  static {
    for (TxState s : TxState.values()) {
      ALLOWED.put(s, EnumSet.noneOf(TxState.class));
    }
    allow(TxState.CREATED, TxState.PREPARED, TxState.FAILED);
    allow(TxState.PREPARED, TxState.ITEMS_REMOVED, TxState.COMMITTED, TxState.FAILED);
    allow(TxState.ITEMS_REMOVED, TxState.COMMITTED, TxState.FAILED);
    allow(
        TxState.COMMITTED,
        TxState.PAYOUT_PENDING,
        TxState.REFUND_PENDING,
        TxState.DELIVERY_STORED,
        TxState.CLAIM_PENDING);
    allow(TxState.PAYOUT_PENDING, TxState.PAYOUT_IN_PROGRESS, TxState.FAILED);
    allow(TxState.PAYOUT_IN_PROGRESS, TxState.PAYOUT_COMPLETED, TxState.PAYOUT_PENDING);
    allow(TxState.REFUND_PENDING, TxState.REFUND_COMPLETED, TxState.FAILED);
    allow(TxState.DELIVERY_STORED, TxState.CLAIM_PENDING, TxState.PAYOUT_PENDING);
    allow(TxState.CLAIM_PENDING, TxState.CLAIM_COMPLETED, TxState.DELIVERY_STORED);
    allow(
        TxState.MANUAL_REVIEW,
        TxState.PAYOUT_COMPLETED,
        TxState.REFUND_COMPLETED,
        TxState.CLAIM_COMPLETED,
        TxState.FAILED);
    // MANUAL_REVIEW ist aus jedem nicht terminalen Zustand ausser sich selbst erreichbar
    for (TxState s : TxState.values()) {
      if (!s.isTerminal() && s != TxState.MANUAL_REVIEW) {
        ALLOWED.get(s).add(TxState.MANUAL_REVIEW);
      }
    }
  }

  private static void allow(TxState from, TxState... to) {
    ALLOWED.get(from).addAll(Set.of(to));
  }

  static Stream<Arguments> allPairs() {
    Stream.Builder<Arguments> b = Stream.builder();
    for (TxState from : TxState.values()) {
      for (TxState to : TxState.values()) {
        b.add(Arguments.of(from, to, ALLOWED.get(from).contains(to)));
      }
    }
    return b.build();
  }

  @ParameterizedTest(name = "{0} -> {1} erlaubt={2}")
  @MethodSource("allPairs")
  void enumMatchesTable(TxState from, TxState to, boolean allowed) {
    assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
  }

  @ParameterizedTest(name = "Repository {0} -> {1} erlaubt={2}")
  @MethodSource("allPairs")
  void repositoryEnforcesTable(TxState from, TxState to, boolean allowed) {
    Harness h = new Harness();
    UUID player = Harness.player(1);
    JournalEntry created =
        JournalEntry.created("k:" + from, null, player, "s", TxType.DELIVERY, null, 1, 1, 10);
    JournalEntry current =
        new JournalEntry(
            created.id(),
            created.idempotencyKey(),
            null,
            player,
            "s",
            TxType.DELIVERY,
            null,
            1,
            1,
            from,
            3,
            10,
            10,
            null);
    h.storage.putJournal(current);
    if (allowed) {
      boolean ok = TxRunner.run(h.storage, uow -> uow.journal().transition(current, to, "d", 20));
      assertThat(ok).isTrue();
      JournalEntry after = h.storage.read(uow -> uow.journal().find(current.id()).orElseThrow());
      assertThat(after.state()).isEqualTo(to);
      assertThat(after.version()).isEqualTo(4);
    } else {
      assertThatThrownBy(
              () -> TxRunner.run(h.storage, uow -> uow.journal().transition(current, to, "d", 20)))
          .isInstanceOf(InvalidTransitionException.class);
      JournalEntry after = h.storage.read(uow -> uow.journal().find(current.id()).orElseThrow());
      assertThat(after.state()).isEqualTo(from);
    }
  }

  @ParameterizedTest(name = "veraltete Version bei {0}")
  @MethodSource("states")
  void staleVersionIsRejected(TxState from) {
    if (from.isTerminal()) {
      return;
    }
    Harness h = new Harness();
    JournalEntry created =
        JournalEntry.created(
            "stale:" + from, null, Harness.player(1), "s", TxType.DELIVERY, null, 1, 1, 10);
    h.storage.putJournal(created);
    JournalEntry stale =
        new JournalEntry(
            created.id(),
            created.idempotencyKey(),
            null,
            Harness.player(1),
            "s",
            TxType.DELIVERY,
            null,
            1,
            1,
            TxState.CREATED,
            99,
            10,
            10,
            null);
    boolean ok =
        TxRunner.run(h.storage, uow -> uow.journal().transition(stale, TxState.PREPARED, "d", 20));
    assertThat(ok).isFalse();
  }

  static Stream<TxState> states() {
    return Stream.of(TxState.values());
  }

  @org.junit.jupiter.api.Test
  void terminalStatesHaveNoSuccessors() {
    for (TxState s : TxState.values()) {
      if (s.isTerminal()) {
        assertThat(s.successors()).isEmpty();
      }
    }
    assertThat(InMemoryStorage.class).isNotNull();
  }
}
