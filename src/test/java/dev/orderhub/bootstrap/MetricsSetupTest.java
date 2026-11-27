package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MetricsSetupTest {

  @Test
  void doesNothingWhenDisabledOrWithoutAPluginId() {
    AtomicInteger started = new AtomicInteger();
    assertThat(MetricsSetup.start(false, 123, started::incrementAndGet))
        .isEqualTo(MetricsSetup.Result.DISABLED_BY_CONFIG);
    assertThat(MetricsSetup.start(true, 0, started::incrementAndGet))
        .isEqualTo(MetricsSetup.Result.NO_PLUGIN_ID);
    assertThat(MetricsSetup.start(true, -4, started::incrementAndGet))
        .isEqualTo(MetricsSetup.Result.NO_PLUGIN_ID);
    assertThat(started).hasValue(0);
  }

  @Test
  void startsOnceWhenEnabledWithAnId() {
    AtomicInteger started = new AtomicInteger();
    assertThat(MetricsSetup.start(true, 4242, started::incrementAndGet))
        .isEqualTo(MetricsSetup.Result.STARTED);
    assertThat(started).hasValue(1);
  }

  @Test
  void orderCountsAreSentOnlyAsCoarseBuckets() {
    assertThat(MetricsSetup.bucket(0)).isEqualTo("0");
    assertThat(MetricsSetup.bucket(9)).isEqualTo("1-9");
    assertThat(MetricsSetup.bucket(10)).isEqualTo("10-99");
    assertThat(MetricsSetup.bucket(999)).isEqualTo("100-999");
    assertThat(MetricsSetup.bucket(1000)).isEqualTo("1000+");
    assertThat(MetricsSetup.bucket(-5)).isEqualTo("0");
    assertThat(MetricsSetup.bucketOf(() -> 50).get()).isEqualTo("10-99");
  }

  @Test
  void theShippedPluginIdIsNotYetRegistered() {
    // Ehrlicher Stand: ohne Registrierung bei bstats.org gibt es keine Id, also sendet 2.0.0
    // nichts.
    assertThat(MetricsSetup.PLUGIN_ID).isZero();
  }
}
