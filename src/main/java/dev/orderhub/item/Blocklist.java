package dev.orderhub.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;

/** Sperrliste fuer Materialien. Unveraenderlich. */
public final class Blocklist {

  /** Ergebnis des Einlesens: Liste plus Warnungen fuer fehlerhafte Eintraege. */
  public record Parsed(Blocklist blocklist, List<String> warnings) {
    public Parsed {
      warnings = List.copyOf(warnings);
    }
  }

  private static final Set<String> DEFAULT_KEYS =
      Set.of(
          "command_block",
          "chain_command_block",
          "repeating_command_block",
          "command_block_minecart",
          "bedrock",
          "barrier",
          "structure_block",
          "structure_void",
          "jigsaw",
          "debug_stick",
          "light",
          "knowledge_book",
          "spawner",
          "trial_spawner",
          "vault",
          "reinforced_deepslate",
          "end_portal_frame",
          "petrified_oak_slab",
          "bundle");

  private final Set<Material> materials;

  private Blocklist(Set<Material> materials) {
    this.materials = Set.copyOf(materials);
  }

  /** Standardliste: Shulker-Boxen, Bundles, Spawn-Eier, Command-Bloecke und Sonderitems. */
  public static Blocklist defaults() {
    return new Blocklist(defaultMaterials());
  }

  /** Leere Liste (nur fuer Tests und bewusste Konfiguration). */
  public static Blocklist empty() {
    return new Blocklist(Set.of());
  }

  public boolean contains(Material material) {
    return materials.contains(material);
  }

  public Set<Material> materials() {
    return materials;
  }

  /**
   * Liest Eintraege: {@code minecraft:stone} oder {@code stone} (Material) und {@code
   * #minecraft:shulker_boxes} (Tag). Ungueltige oder unbekannte Eintraege ergeben eine Warnung.
   *
   * @param includeDefaults Standardliste als Grundlage verwenden
   */
  public static Parsed parse(Collection<String> entries, boolean includeDefaults) {
    List<String> warnings = new ArrayList<>();
    Set<Material> result = includeDefaults ? defaultMaterials() : EnumSet.noneOf(Material.class);
    for (String raw : entries == null ? List.<String>of() : entries) {
      String entry = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
      if (entry.isEmpty()) {
        warnings.add("blank blocklist entry");
        continue;
      }
      boolean tag = entry.startsWith("#");
      NamespacedKey key = NamespacedKey.fromString(tag ? entry.substring(1) : entry);
      if (key == null) {
        warnings.add("invalid key: " + raw);
      } else if (tag) {
        addTag(result, key, raw, warnings);
      } else {
        Material material = Registry.MATERIAL.get(key);
        if (material == null) {
          warnings.add("unknown material: " + raw);
        } else {
          result.add(material);
        }
      }
    }
    return new Parsed(new Blocklist(result), warnings);
  }

  private static void addTag(
      Set<Material> target, NamespacedKey key, String raw, List<String> warnings) {
    Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
    if (tag == null || tag.getValues().isEmpty()) {
      warnings.add("unknown or empty tag: " + raw);
    } else {
      target.addAll(tag.getValues());
    }
  }

  private static Set<Material> defaultMaterials() {
    Set<Material> set = EnumSet.noneOf(Material.class);
    Registry.MATERIAL.stream()
        .filter(Material::isItem)
        .filter(Blocklist::isDefaultBlocked)
        .forEach(set::add);
    return set;
  }

  private static boolean isDefaultBlocked(Material material) {
    String path = Objects.requireNonNull(material.getKey()).getKey();
    return DEFAULT_KEYS.contains(path)
        || path.endsWith("shulker_box")
        || path.endsWith("_bundle")
        || path.endsWith("_spawn_egg");
  }
}
