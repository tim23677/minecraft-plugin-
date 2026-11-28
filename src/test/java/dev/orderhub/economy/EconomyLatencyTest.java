package dev.orderhub.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EconomyLatencyTest {

  @Test
  void emptyUntilSomethingIsRecorded() {
    assertThat(new EconomyLatency(10).snapshot()).isEqualTo(EconomyLatency.Snapshot.EMPTY);
  }

  @Test
  void reportsMedianMaxAndLast() {
    EconomyLatency latency = new EconomyLatency(10);
    for (long v : new long[] {5, 1, 9, 3, 7}) {
      latency.record(v);
    }
    var snap = latency.snapshot();
    assertThat(snap.samples()).isEqualTo(5);
    assertThat(snap.medianMillis()).isEqualTo(5);
    assertThat(snap.maxMillis()).isEqualTo(9);
    assertThat(snap.lastMillis()).isEqualTo(7);
  }

  @Test
  void theRingKeepsOnlyTheNewestSamples() {
    EconomyLatency latency = new EconomyLatency(4);
    latency.record(1000); // wird ueberschrieben
    for (int i = 0; i < 4; i++) {
      latency.record(10);
    }
    var snap = latency.snapshot();
    assertThat(snap.samples()).isEqualTo(4);
    assertThat(snap.maxMillis()).isEqualTo(10);
  }

  @Test
  void negativeValuesCountAsZeroAndBadCapacityIsRejected() {
    EconomyLatency latency = new EconomyLatency(2);
    latency.record(-5);
    assertThat(latency.snapshot().maxMillis()).isZero();
    assertThatThrownBy(() -> new EconomyLatency(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentRecordingNeverThrowsOrExceedsTheCapacity() throws Exception {
    EconomyLatency latency = new EconomyLatency(50);
    Thread[] threads = new Thread[4];
    for (int t = 0; t < threads.length; t++) {
      threads[t] =
          new Thread(() -> {
            for (int i = 0; i < 5_000; i++) {
              latency.record(i % 100);
            }
          });
      threads[t].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(latency.snapshot().samples()).isEqualTo(50);
    assertThat(latency.snapshot().maxMillis()).isLessThan(100);
  }
}
