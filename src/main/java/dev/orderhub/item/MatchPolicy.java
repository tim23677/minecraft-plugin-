package dev.orderhub.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.NamespacedKey;

/**
 * Welche Abweichungen vom Standarditem zugelassen sind. Standard ({@link #strict()}) lehnt alles
 * ab. Ausnahmen gibt es je Kriterium ({@link RejectionReason}) und je Datenkomponente (Namespaced
 * Key, nur fuer {@link RejectionReason#UNEXPECTED_COMPONENT}).
 *
 * @param allowedCriteria zugelassene Kriterien (nur CUSTOM_NAME, LORE, ENCHANTED, DAMAGED,
 *     CONTAINER_CONTENTS, UNEXPECTED_COMPONENT)
 * @param allowedComponents zugelassene Komponentenschluessel, z. B. {@code minecraft:repair_cost}
 */
public record MatchPolicy(Set<RejectionReason> allowedCriteria, Set<String> allowedComponents) {

  /** Kriterien, fuer die eine Ausnahme ueberhaupt erlaubt werden kann. */
  public static final Set<RejectionReason> EXEMPTABLE =
      Set.copyOf(
          EnumSet.of(
              RejectionReason.CUSTOM_NAME,
              RejectionReason.LORE,
              RejectionReason.ENCHANTED,
              RejectionReason.DAMAGED,
              RejectionReason.CONTAINER_CONTENTS,
              RejectionReason.UNEXPECTED_COMPONENT));

  public MatchPolicy {
    Objects.requireNonNull(allowedCriteria, "allowedCriteria");
    Objects.requireNonNull(allowedComponents, "allowedComponents");
    for (RejectionReason reason : allowedCriteria) {
      if (!EXEMPTABLE.contains(reason)) {
        throw new IllegalArgumentException("criterion cannot be exempted: " + reason);
      }
    }
    allowedCriteria = Set.copyOf(allowedCriteria);
    allowedComponents = Set.copyOf(allowedComponents);
  }

  /** Standard: keine Ausnahmen. */
  public static MatchPolicy strict() {
    return new MatchPolicy(Set.of(), Set.of());
  }

  /** Kopie mit zusaetzlich zugelassenen Kriterien. */
  public MatchPolicy allowing(RejectionReason... criteria) {
    Set<RejectionReason> merged = new HashSet<>(allowedCriteria);
    merged.addAll(List.of(criteria));
    return new MatchPolicy(merged, allowedComponents);
  }

  /** Kopie mit zusaetzlich zugelassenen Komponentenschluesseln. */
  public MatchPolicy allowingComponents(String... componentKeys) {
    Set<String> merged = new HashSet<>(allowedComponents);
    merged.addAll(List.of(componentKeys));
    return new MatchPolicy(allowedCriteria, merged);
  }

  public boolean allows(RejectionReason reason) {
    return allowedCriteria.contains(reason);
  }

  /** Ergebnis des Einlesens aus der Konfiguration. */
  public record Parsed(MatchPolicy policy, List<String> warnings) {
    public Parsed {
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * Liest Konfigurationslisten. Fehlerhafte Eintraege werden uebersprungen und als Warnung
   * gemeldet, es gibt keine Exception.
   */
  public static Parsed parse(
      Collection<String> criteriaEntries, Collection<String> componentEntries) {
    List<String> warnings = new ArrayList<>();
    Set<RejectionReason> criteria = EnumSet.noneOf(RejectionReason.class);
    for (String entry : criteriaEntries == null ? List.<String>of() : criteriaEntries) {
      RejectionReason reason = parseCriterion(entry);
      if (reason == null || !EXEMPTABLE.contains(reason)) {
        warnings.add("unknown or non-exemptable criterion: " + entry);
      } else {
        criteria.add(reason);
      }
    }
    Set<String> components = new HashSet<>();
    for (String entry : componentEntries == null ? List.<String>of() : componentEntries) {
      NamespacedKey key =
          entry == null ? null : NamespacedKey.fromString(entry.trim().toLowerCase(Locale.ROOT));
      if (key == null) {
        warnings.add("invalid component key: " + entry);
      } else {
        components.add(key.toString());
      }
    }
    return new Parsed(new MatchPolicy(criteria, components), warnings);
  }

  private static RejectionReason parseCriterion(String entry) {
    if (entry == null) {
      return null;
    }
    try {
      return RejectionReason.valueOf(entry.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
