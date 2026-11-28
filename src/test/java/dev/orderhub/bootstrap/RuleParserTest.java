package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Rules;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RuleParserTest {

  private static YamlConfiguration yaml(String text) throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    y.loadFromString(text);
    return y;
  }

  @Test
  void emptyConfigurationGivesNoRules() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules = RuleParser.parse(yaml("items: {}\n"), warnings);
    assertThat(rules.priceBands()).isEmpty();
    assertThat(rules.feeTiers()).isEmpty();
    assertThat(rules.feeWindows()).isEmpty();
    assertThat(warnings).isEmpty();
  }

  @Test
  void priceLimitsAcceptSuffixesAndAllWaysToWriteAnItem() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules =
        RuleParser.parse(
            yaml(
                """
                items:
                  price-limits:
                    diamond: {min: "5", max: "1.5k"}
                    NETHERITE_INGOT: {min: "100", max: "1m"}
                    "custom:thing": {min: "1", max: "2"}
                """),
            warnings);
    assertThat(rules.priceBands())
        .containsOnlyKeys("minecraft:diamond", "minecraft:netherite_ingot", "custom:thing");
    assertThat(rules.priceBands().get("minecraft:diamond").min()).isEqualTo(500);
    assertThat(rules.priceBands().get("minecraft:diamond").max()).isEqualTo(150_000);
    assertThat(warnings).isEmpty();
  }

  @Test
  void brokenPriceLimitsAreSkippedWithAWarning() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules =
        RuleParser.parse(
            yaml(
                """
                items:
                  price-limits:
                    diamond: {min: "abc", max: "5"}
                    gold_ingot: {min: "10", max: "1"}
                    iron_ingot: 5
                    emerald: {min: "1", max: "9"}
                """),
            warnings);
    assertThat(rules.priceBands()).containsOnlyKeys("minecraft:emerald");
    assertThat(warnings).hasSize(3);
  }

  @Test
  void feeTiersNeedAPermissionAndAValidRate() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules =
        RuleParser.parse(
            yaml(
                """
                fees:
                  tiers:
                    vip: {permission: orderhub.fee.vip, create-fee-bp: 50}
                    nothing: {create-fee-bp: 10}
                    toohigh: {permission: x.y, create-fee-bp: 20000}
                    text: {permission: x.z, create-fee-bp: "ten"}
                """),
            warnings);
    assertThat(rules.feeTiers()).containsExactly(new Fees.Tier("orderhub.fee.vip", 50));
    assertThat(warnings).hasSize(3);
  }

  @Test
  void windowsReadDaysTimesAndRates() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules =
        RuleParser.parse(
            yaml(
                """
                events:
                  timezone: Europe/Berlin
                  windows:
                    weekend: {days: [saturday, SUNDAY], from: "00:00", to: "23:59", create-fee-bp: 0}
                    badday: {days: [FUNDAY], create-fee-bp: 0}
                    badtime: {from: "25:99", create-fee-bp: 0}
                    norate: {days: [MONDAY]}
                """),
            warnings);
    assertThat(rules.zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
    assertThat(rules.feeWindows()).hasSize(1);
    var w = rules.feeWindows().get(0);
    assertThat(w.days()).containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    assertThat(w.from()).isEqualTo(LocalTime.MIDNIGHT);
    assertThat(w.createFeeBp()).isZero();
    assertThat(w.salesTaxBp()).isNull();
    assertThat(warnings).hasSize(3);
  }

  @Test
  void anUnknownTimeZoneFallsBackWithAWarning() throws Exception {
    List<String> warnings = new ArrayList<>();
    Rules rules = RuleParser.parse(yaml("events:\n  timezone: Mars/Olympus\n"), warnings);
    assertThat(rules.zone()).isEqualTo(ZoneId.systemDefault());
    assertThat(warnings).hasSize(1);
  }

  @Test
  void parsedRulesDriveTheFeesEndToEnd() throws Exception {
    Rules rules =
        RuleParser.parse(
            yaml(
                """
                fees:
                  tiers:
                    vip: {permission: orderhub.fee.vip, create-fee-bp: 40}
                events:
                  timezone: UTC
                  windows:
                    sat: {days: [SATURDAY], from: "10:00", to: "12:00", create-fee-bp: 0}
                """),
            new ArrayList<>());
    Fees fees = new Fees(100, 200, true, rules.feeTiers(), rules.feeWindows(), rules.zone());
    Instant saturdayNoon = Instant.parse("2026-11-28T11:00:00Z");
    Instant saturdayLate = Instant.parse("2026-11-28T13:00:00Z");
    assertThat(fees.createFeeBpAt(p -> false, saturdayNoon)).isZero();
    assertThat(fees.createFeeBpAt(p -> false, saturdayLate)).isEqualTo(100);
    assertThat(fees.createFeeBpAt(p -> p.equals("orderhub.fee.vip"), saturdayLate)).isEqualTo(40);
  }
}
