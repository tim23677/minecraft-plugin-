package dev.orderhub.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mitgelieferte Voreinstellungen fuer {@code items.component-presets}. Jede Voreinstellung laesst
 * eine Gruppe von Items in Auftraegen zu, deren Besonderheit in einer Datenkomponente liegt.
 * Zulassen heisst nicht tolerieren: Der Abgleich vergleicht diese Komponenten weiter exakt (siehe
 * {@link ItemMatcher}).
 *
 * @since 2.0.0
 */
public final class ComponentPresets {

  private ComponentPresets() {}

  private static final Map<String, List<String>> PRESETS = presets();

  private static Map<String, List<String>> presets() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    // Traenke, Wurftraenke und Verweiltraenke unterscheiden sich im Material, der Effekt steht in
    // der Komponente.
    map.put("potions", List.of("minecraft:potion_contents", "minecraft:potion_duration_scale"));
    map.put("arrows", List.of("minecraft:potion_contents"));
    map.put("fireworks", List.of("minecraft:fireworks", "minecraft:firework_explosion"));
    map.put("goat-horns", List.of("minecraft:instrument"));
    map.put("banners", List.of("minecraft:banner_patterns"));
    map.put("ominous-bottles", List.of("minecraft:ominous_bottle_amplifier"));
    map.put("player-heads", List.of("minecraft:profile"));
    return Map.copyOf(map);
  }

  /** Namen aller Voreinstellungen. */
  public static Set<String> names() {
    return new LinkedHashSet<>(
        List.of(
            "potions",
            "arrows",
            "fireworks",
            "goat-horns",
            "banners",
            "ominous-bottles",
            "player-heads"));
  }

  /** Ergebnis der Aufloesung. */
  public record Resolved(Set<String> componentKeys, List<String> warnings) {
    public Resolved {
      componentKeys = Set.copyOf(componentKeys);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * Loest Voreinstellungen zu Komponentenschluesseln auf. Unbekannte Namen werden uebersprungen und
   * als Warnung gemeldet. Der Name {@code all} steht fuer alle Voreinstellungen.
   */
  public static Resolved resolve(Collection<String> presetNames) {
    Set<String> keys = new LinkedHashSet<>();
    List<String> warnings = new ArrayList<>();
    for (String raw : presetNames == null ? List.<String>of() : presetNames) {
      String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
      if (name.equals("all")) {
        PRESETS.values().forEach(keys::addAll);
      } else if (PRESETS.containsKey(name)) {
        keys.addAll(PRESETS.get(name));
      } else {
        warnings.add("unknown component preset: " + raw);
      }
    }
    return new Resolved(keys, warnings);
  }
}
