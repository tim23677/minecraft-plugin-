package dev.orderhub.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Steuerbare Uhr fuer Tests. */
public final class MutableClock extends Clock {
  private final AtomicLong millis;

  public MutableClock(long start) {
    this.millis = new AtomicLong(start);
  }

  public void advance(long ms) {
    millis.addAndGet(ms);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public long millis() {
    return millis.get();
  }

  @Override
  public Instant instant() {
    return Instant.ofEpochMilli(millis.get());
  }
}
