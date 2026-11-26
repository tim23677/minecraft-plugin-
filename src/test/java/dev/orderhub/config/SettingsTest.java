package dev.orderhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.money.Money;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {

  @Test
  void defaultsFromBundledConfigHaveNoWarnings() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      yaml.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }
    Settings s = Settings.from(yaml);
    assertThat(s.warnings()).isEmpty();
    assertThat(s.minUnitPrice()).isEqualTo(1);
    assertThat(s.maxUnitPrice()).isEqualTo(Money.parse("1000000"));
    assertThat(s.serverId()).isEqualTo("main");
    assertThat(s.createFeeBp()).isEqualTo(100);
  }

  @Test
  void invalidValuesFallBackWithWarning() {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("server-id", "Bad Id!");
    yaml.set("orders.max-quantity", -5);
    yaml.set("orders.min-unit-price", "abc");
    yaml.set("fees.sales-tax-bp", 99_999);
    Settings s = Settings.from(yaml);
    assertThat(s.serverId()).isEqualTo("main");
    assertThat(s.maxQuantity()).isEqualTo(2304);
    assertThat(s.salesTaxBp()).isEqualTo(200);
    assertThat(s.warnings()).hasSizeGreaterThanOrEqualTo(4);
  }

  @Test
  void invertedPriceLimitsAreRejected() {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("orders.min-unit-price", "10");
    yaml.set("orders.max-unit-price", "5");
    Settings s = Settings.from(yaml);
    assertThat(s.minUnitPrice()).isEqualTo(1);
    assertThat(s.warnings()).anyMatch(w -> w.contains("price limits"));
  }
}
