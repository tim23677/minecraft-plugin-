package dev.orderhub.testsupport;

import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Storage-Huelle, die vor der n-ten Transaktion eine Aktion ausfuehrt (z. B. ein anderer Server
 * schreibt einen Eintrag fort, zwischen Auflistung und Verarbeitung).
 */
public final class InterceptingStorage implements Storage {

  private final Storage delegate;
  private final AtomicInteger calls = new AtomicInteger();
  private final int beforeCall;
  private final Runnable action;
  private volatile boolean fired;

  public InterceptingStorage(Storage delegate, int beforeCall, Runnable action) {
    this.delegate = delegate;
    this.beforeCall = beforeCall;
    this.action = action;
  }

  public boolean fired() {
    return fired;
  }

  public int calls() {
    return calls.get();
  }

  @Override
  public <T> CompletableFuture<T> transact(Function<UnitOfWork, T> work) {
    if (calls.incrementAndGet() == beforeCall) {
      fired = true;
      action.run();
    }
    return delegate.transact(work);
  }

  @Override
  public Dialect dialect() {
    return delegate.dialect();
  }

  @Override
  public String serverId() {
    return delegate.serverId();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
