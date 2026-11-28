package dev.orderhub.economy;

import dev.orderhub.scheduler.PluginScheduler;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Fuehrt jeden Aufruf des Providers auf dem globalen Server-Thread aus und gibt das Ergebnis mit
 * Zeitlimit an den aufrufenden Worker zurueck. Viele Economy-Plugins (EssentialsX, CMI) sind nicht
 * fuer Async-Aufrufe gebaut.
 *
 * <p>Laeuft der Aufrufer schon auf dem globalen Thread, wird direkt aufgerufen (Warten wuerde
 * blockieren). Wurde der Task bis zum Timeout nie gestartet (Zustand ABANDONED), wird er verworfen,
 * ruft den Provider nie auf und das Ergebnis ist {@link EconomyResult#UNAVAILABLE}: Es ist sicher
 * kein Geld geflossen, der Aufrufer darf wiederholen. Nur ein gestarteter, aber nicht beendeter
 * {@code withdraw} oder {@code deposit} gilt als {@link EconomyOutcomeUnknownException}, weil der
 * Provider die Operation noch ausfuehren kann. Bei {@code has} fliesst nie Geld, dort ist ein
 * Timeout immer {@link EconomyResult#UNAVAILABLE}.
 */
public final class GlobalThreadEconomy implements EconomyBridge {

  private static final int PENDING = 0;
  private static final int STARTED = 1;
  private static final int ABANDONED = 2;

  private final EconomyBridge delegate;
  private final PluginScheduler scheduler;
  private final BooleanSupplier onGlobalThread;
  private final Duration timeout;
  private final EconomyLatency latency = new EconomyLatency(200);

  /** Dauer der letzten Aufrufe, fuer {@code /orderadmin health}. */
  public EconomyLatency latency() {
    return latency;
  }

  /**
   * @param onGlobalThread liefert {@code true}, wenn der aktuelle Thread der globale (Paper: Main)
   *     Thread ist
   */
  public GlobalThreadEconomy(
      EconomyBridge delegate,
      PluginScheduler scheduler,
      BooleanSupplier onGlobalThread,
      Duration timeout) {
    this.delegate = delegate;
    this.scheduler = scheduler;
    this.onGlobalThread = onGlobalThread;
    this.timeout = timeout;
  }

  @Override
  public EconomyResult has(UUID player, long minor) {
    return call(() -> delegate.has(player, minor), EconomyResult.UNAVAILABLE, null);
  }

  @Override
  public EconomyResult withdraw(UUID player, long minor, String reference) {
    return call(
        () -> delegate.withdraw(player, minor, reference),
        null,
        "withdraw " + reference + ": timeout");
  }

  @Override
  public EconomyResult deposit(UUID player, long minor, String reference) {
    return call(
        () -> delegate.deposit(player, minor, reference),
        null,
        "deposit " + reference + ": timeout");
  }

  private EconomyResult call(
      Supplier<EconomyResult> op, EconomyResult onTimeout, String unknownMessage) {
    if (onGlobalThread.getAsBoolean()) {
      return op.get();
    }
    AtomicInteger state = new AtomicInteger(PENDING);
    CompletableFuture<EconomyResult> future = new CompletableFuture<>();
    long startedAt = System.nanoTime();
    try {
      scheduler.runGlobal(() -> {
        if (!state.compareAndSet(PENDING, STARTED)) {
          return;
        }
        try {
          EconomyResult result = op.get();
          latency.record((System.nanoTime() - startedAt) / 1_000_000);
          future.complete(result);
        } catch (RuntimeException | Error e) {
          future.completeExceptionally(e);
        }
      });
    } catch (RuntimeException e) {
      // Task nie eingeplant: der Provider wurde nicht aufgerufen.
      return onTimeout != null ? onTimeout : EconomyResult.UNAVAILABLE;
    }
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      latency.record(timeout.toMillis());
      if (future.isDone() && !future.isCompletedExceptionally()) {
        // Der Aufruf ist zwischen Zeitablauf und Pruefung fertig geworden.
        return future.join();
      }
      if (state.compareAndSet(PENDING, ABANDONED)) {
        // Nie gestartet: der Provider wurde sicher nicht aufgerufen, es ist kein Geld geflossen.
        // UNAVAILABLE laesst den Aufrufer mit Backoff wiederholen, statt in die manuelle Pruefung
        // zu eskalieren.
        return EconomyResult.UNAVAILABLE;
      }
      // Gestartet, aber nicht beendet: der Provider kann die Operation noch ausfuehren.
      return failTimeout(onTimeout, unknownMessage, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (state.compareAndSet(PENDING, ABANDONED)) {
        return EconomyResult.UNAVAILABLE;
      }
      return failTimeout(onTimeout, unknownMessage, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new EconomyOutcomeUnknownException(String.valueOf(unknownMessage), cause);
    }
  }

  private static EconomyResult failTimeout(
      EconomyResult onTimeout, String unknownMessage, Throwable cause) {
    if (onTimeout != null) {
      return onTimeout;
    }
    throw new EconomyOutcomeUnknownException(unknownMessage, cause);
  }
}
