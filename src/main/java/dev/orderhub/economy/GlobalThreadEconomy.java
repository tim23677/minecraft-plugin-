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
 * blockieren). Eine Zeitueberschreitung bei {@code withdraw} oder {@code deposit} gilt als {@link
 * EconomyOutcomeUnknownException}, weil der Provider die Operation spaeter noch ausfuehren kann.
 * Bei {@code has} fliesst kein Geld, dort ist das Ergebnis {@link EconomyResult#UNAVAILABLE}. Wurde
 * der Task vor dem Timeout nicht gestartet, wird er verworfen und ruft den Provider nie auf.
 */
public final class GlobalThreadEconomy implements EconomyBridge {

  private static final int PENDING = 0;
  private static final int STARTED = 1;
  private static final int ABANDONED = 2;

  private final EconomyBridge delegate;
  private final PluginScheduler scheduler;
  private final BooleanSupplier onGlobalThread;
  private final Duration timeout;

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
    try {
      scheduler.runGlobal(() -> {
        if (!state.compareAndSet(PENDING, STARTED)) {
          return;
        }
        try {
          future.complete(op.get());
        } catch (RuntimeException e) {
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
      if (state.compareAndSet(PENDING, ABANDONED)) {
        // Nie gestartet: sicher nicht ausgefuehrt. Trotzdem wie vereinbart als unklar melden.
        return failTimeout(onTimeout, unknownMessage, e);
      }
      return failTimeout(onTimeout, unknownMessage, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (state.compareAndSet(PENDING, ABANDONED)) {
        return onTimeout != null ? onTimeout : EconomyResult.UNAVAILABLE;
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
