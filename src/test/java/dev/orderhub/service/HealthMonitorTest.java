package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.economy.EconomyLatency;
import dev.orderhub.storage.Storage;
import org.junit.jupiter.api.Test;

class HealthMonitorTest {

  private static final HealthMonitor.Thresholds LIMITS =
      new HealthMonitor.Thresholds(1000, 50, 10, 1);

  private static AdminService.Health health(
      long medianMs, int open, long oldestMinutes, int review) {
    return new AdminService.Health(
        new Storage.Health(0, 0, 0, 0, 0, 0),
        open,
        0,
        review,
        0,
        null,
        oldestMinutes * 60_000,
        new EconomyLatency.Snapshot(10, medianMs, medianMs, medianMs));
  }

  @Test
  void healthyValuesGiveNoWarnings() {
    assertThat(new HealthMonitor().check(health(20, 3, 0, 0), LIMITS)).isEmpty();
  }

  @Test
  void eachExceededLimitIsReportedOnceUntilItRecovers() {
    HealthMonitor monitor = new HealthMonitor();
    var first = monitor.check(health(2500, 80, 30, 2), LIMITS);
    assertThat(first).hasSize(4);
    assertThat(first.get(0)).contains("2500").contains("1000");
    assertThat(monitor.check(health(2500, 80, 30, 2), LIMITS)).isEmpty(); // gleiche Lage: still
    assertThat(monitor.check(health(20, 3, 0, 0), LIMITS)).isEmpty(); // erholt
    assertThat(monitor.check(health(2500, 3, 0, 0), LIMITS)).hasSize(1); // erneut ueberschritten
  }

  @Test
  void aThresholdOfZeroDisablesThatCheck() {
    var off = new HealthMonitor.Thresholds(0, 0, 0, 0);
    assertThat(new HealthMonitor().check(health(99_999, 99_999, 99_999, 99), off)).isEmpty();
  }

  @Test
  void theBoundaryIsInclusive() {
    assertThat(new HealthMonitor().check(health(1000, 3, 0, 0), LIMITS)).hasSize(1);
    assertThat(new HealthMonitor().check(health(999, 3, 0, 0), LIMITS)).isEmpty();
  }
}
