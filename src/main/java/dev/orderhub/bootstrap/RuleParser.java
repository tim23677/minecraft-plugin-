package dev.orderhub.bootstrap;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.config.Rules;
import dev.orderhub.money.Money;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Liest {@code items.price-limits}, {@code fees.tiers} und {@code events} aus der Konfiguration.
 * Fehlerhafte Eintraege werden mit einer Warnung uebersprungen, nie mit einer Ausnahme.
 *
 * @since 2.0.0
 */
public final class RuleParser {

  private RuleParser() {}

  public static Rules parse(ConfigurationSection cfg, List<String> warnings) {
    return new Rules(
        priceBands(cfg.getConfigurationSection("items.price-limits"), warnings),
        tiers(cfg.getConfigurationSection("fees.tiers"), warnings),
        windows(cfg.getConfigurationSection("events.windows"), warnings),
        zone(cfg.getString("events.timezone", ""), warnings));
  }

  /** {@code diamond}, {@code DIAMOND} und {@code minecraft:diamond} sind dasselbe Item. */
  static String itemKey(String raw) {
    String key = raw.trim().toLowerCase(Locale.ROOT);
    return key.contains(":") ? key : "minecraft:" + key;
  }

  private static Map<String, Limits.PriceBand> priceBands(
      ConfigurationSection section, List<String> warnings) {
    Map<String, Limits.PriceBand> out = new LinkedHashMap<>();
    if (section == null) {
      return out;
    }
    for (String name : section.getKeys(false)) {
      ConfigurationSection entry = section.getConfigurationSection(name);
      if (entry == null) {
        warnings.add("items.price-limits." + name + " needs min and max, entry ignored");
        continue;
      }
      try {
        long min = Money.parse(entry.getString("min", "0.01"));
        long max = Money.parse(entry.getString("max", Long.toString(Money.MAX)));
        out.put(itemKey(name), new Limits.PriceBand(min, max));
      } catch (RuntimeException e) {
        warnings.add(
            "items.price-limits." + name + " is invalid (" + e.getMessage() + "), entry ignored");
      }
    }
    return out;
  }

  private static List<Fees.Tier> tiers(ConfigurationSection section, List<String> warnings) {
    List<Fees.Tier> out = new ArrayList<>();
    if (section == null) {
      return out;
    }
    for (String name : section.getKeys(false)) {
      ConfigurationSection entry = section.getConfigurationSection(name);
      String permission = entry == null ? "" : entry.getString("permission", "");
      if (entry == null || permission.isBlank() || !entry.isInt("create-fee-bp")) {
        warnings.add("fees.tiers." + name + " needs permission and create-fee-bp, entry ignored");
        continue;
      }
      try {
        out.add(new Fees.Tier(permission.trim(), entry.getInt("create-fee-bp")));
      } catch (IllegalArgumentException e) {
        warnings.add("fees.tiers." + name + ": create-fee-bp must be 0 to 10000, entry ignored");
      }
    }
    return out;
  }

  private static List<Fees.Window> windows(ConfigurationSection section, List<String> warnings) {
    List<Fees.Window> out = new ArrayList<>();
    if (section == null) {
      return out;
    }
    for (String name : section.getKeys(false)) {
      ConfigurationSection entry = section.getConfigurationSection(name);
      if (entry == null) {
        warnings.add("events.windows." + name + " is not a section, entry ignored");
        continue;
      }
      try {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : entry.getStringList("days")) {
          days.add(DayOfWeek.valueOf(day.trim().toUpperCase(Locale.ROOT)));
        }
        LocalTime from = LocalTime.parse(entry.getString("from", "00:00"));
        LocalTime to = LocalTime.parse(entry.getString("to", "23:59"));
        Integer create = entry.isInt("create-fee-bp") ? entry.getInt("create-fee-bp") : null;
        Integer tax = entry.isInt("sales-tax-bp") ? entry.getInt("sales-tax-bp") : null;
        if (create == null && tax == null) {
          warnings.add(
              "events.windows."
                  + name
                  + " sets neither create-fee-bp nor sales-tax-bp, entry ignored");
          continue;
        }
        out.add(new Fees.Window(name, days, from, to, create, tax));
      } catch (IllegalArgumentException | DateTimeException e) {
        warnings.add("events.windows." + name + " is invalid (days, from or to), entry ignored");
      }
    }
    return out;
  }

  private static ZoneId zone(String raw, List<String> warnings) {
    if (raw == null || raw.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(raw.trim());
    } catch (DateTimeException e) {
      warnings.add("events.timezone '" + raw + "' is unknown, using the server time zone");
      return ZoneId.systemDefault();
    }
  }
}
