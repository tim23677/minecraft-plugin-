package dev.orderhub.testsupport;

import dev.orderhub.economy.EconomyBridge;
import dev.orderhub.economy.EconomyOutcomeUnknownException;
import dev.orderhub.economy.EconomyResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Thread-sichere Test-Economy mit Fehlerinjektion und Doppelzahlungs-Erkennung je Referenz. */
public final class InMemoryEconomy implements EconomyBridge {

  /** Operation. */
  public enum Op {
    WITHDRAW,
    DEPOSIT
  }

  /** Fehlermodus fuer die naechste Operation. */
  public enum Mode {
    /** Provider lehnt sicher ab. */
    RETURN_FAILED,
    /** Kein Provider. */
    RETURN_UNAVAILABLE,
    /** Ausnahme, Operation wurde NICHT ausgefuehrt (dem Aufrufer aber unbekannt). */
    THROW_BEFORE,
    /** Operation ausgefuehrt, danach Ausnahme (unklarer Ausgang). */
    THROW_AFTER,
    /** Operation ausgefuehrt, danach Prozessabbruch. */
    CRASH_AFTER
  }

  /** Eine ausgefuehrte Mutation. */
  public record Executed(Op op, UUID player, long amount, String reference) {}

  private final Map<UUID, Long> balances = new HashMap<>();
  private final Map<Op, Deque<Mode>> faults = new HashMap<>();
  private final List<Executed> executed = new ArrayList<>();
  private final Set<String> references = new HashSet<>();
  private final List<String> duplicateReferences = new ArrayList<>();
  private boolean unavailable;
  private int crashAfterOps = -1;

  public synchronized void setBalance(UUID player, long minor) {
    balances.put(player, minor);
  }

  public synchronized long balance(UUID player) {
    return balances.getOrDefault(player, 0L);
  }

  public synchronized long total() {
    return balances.values().stream().mapToLong(Long::longValue).sum();
  }

  public synchronized void setUnavailable(boolean value) {
    unavailable = value;
  }

  public synchronized void failNext(Op op, Mode mode) {
    faults.computeIfAbsent(op, k -> new ArrayDeque<>()).add(mode);
  }

  /** Nach der n-ten ausgefuehrten Mutation (ab jetzt gezaehlt) Prozessabbruch. */
  public synchronized void crashAfterMutation(int n) {
    crashAfterOps = executed.size() + n;
  }

  public synchronized void clearFaults() {
    faults.clear();
    crashAfterOps = -1;
    unavailable = false;
  }

  public synchronized int mutationCount() {
    return executed.size();
  }

  public synchronized List<Executed> executed() {
    return List.copyOf(executed);
  }

  public synchronized List<String> duplicateReferences() {
    return List.copyOf(duplicateReferences);
  }

  public synchronized int count(Op op) {
    return (int) executed.stream().filter(e -> e.op() == op).count();
  }

  @Override
  public synchronized EconomyResult has(UUID player, long minor) {
    if (unavailable) {
      return EconomyResult.UNAVAILABLE;
    }
    return balance(player) >= minor ? EconomyResult.OK : EconomyResult.INSUFFICIENT;
  }

  @Override
  public synchronized EconomyResult withdraw(UUID player, long minor, String reference) {
    return execute(Op.WITHDRAW, player, minor, reference);
  }

  @Override
  public synchronized EconomyResult deposit(UUID player, long minor, String reference) {
    return execute(Op.DEPOSIT, player, minor, reference);
  }

  private EconomyResult execute(Op op, UUID player, long minor, String reference) {
    if (unavailable) {
      return EconomyResult.UNAVAILABLE;
    }
    Deque<Mode> queue = faults.get(op);
    Mode mode = queue == null ? null : queue.poll();
    if (mode == Mode.RETURN_FAILED) {
      return EconomyResult.FAILED;
    }
    if (mode == Mode.RETURN_UNAVAILABLE) {
      return EconomyResult.UNAVAILABLE;
    }
    if (mode == Mode.THROW_BEFORE) {
      throw new EconomyOutcomeUnknownException("injected before " + op, null);
    }
    if (minor <= 0) {
      return EconomyResult.FAILED;
    }
    if (op == Op.WITHDRAW && balance(player) < minor) {
      return EconomyResult.INSUFFICIENT;
    }
    if (!references.add(op + ":" + reference)) {
      duplicateReferences.add(op + ":" + reference);
    }
    balances.merge(player, op == Op.WITHDRAW ? -minor : minor, Long::sum);
    executed.add(new Executed(op, player, minor, reference));
    if (mode == Mode.THROW_AFTER) {
      throw new EconomyOutcomeUnknownException("injected after " + op, null);
    }
    if (mode == Mode.CRASH_AFTER || (crashAfterOps >= 0 && executed.size() == crashAfterOps)) {
      crashAfterOps = -1;
      throw new SimulatedCrash("crash after " + op + " " + reference);
    }
    return EconomyResult.OK;
  }
}
