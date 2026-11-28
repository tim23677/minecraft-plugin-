package dev.orderhub.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Gestaltung eines Menues aus {@code menus.yml}: Zeilen, Titel, Slots, Materialien und Texte.
 * Fehlende oder ungueltige Angaben fallen auf die Vorgaben des Codes zurueck und erzeugen eine
 * Warnung, nie eine Exception. Texte sind MiniMessage.
 *
 * @param rows Zeilen 1 bis 6
 * @param title eigener Titel (MiniMessage), leer = Titel aus der Sprachdatei
 * @param slots benannte Slot-Gruppen
 * @param materials benannte Materialien
 * @param texts benannte Texte (MiniMessage), leer = Text aus der Sprachdatei
 * @param warnings Hinweise zu ungueltigen Angaben
 * @since 2.0.0
 */
public record MenuLayout(
    int rows,
    String title,
    Map<String, List<Integer>> slots,
    Map<String, Material> materials,
    Map<String, String> texts,
    List<String> warnings) {

  public MenuLayout {
    if (rows < 1 || rows > 6) {
      throw new IllegalArgumentException("rows must be 1..6");
    }
    title = title == null ? "" : title;
    slots = Map.copyOf(slots);
    materials = Map.copyOf(materials);
    texts = Map.copyOf(texts);
    warnings = List.copyOf(warnings);
  }

  /** Vorgaben des Codes ohne Konfiguration. */
  public static MenuLayout defaults(int rows) {
    return new MenuLayout(rows, "", Map.of(), Map.of(), Map.of(), List.of());
  }

  /**
   * Liest den Abschnitt eines Menues. {@code section} darf {@code null} sein.
   *
   * @param name Name des Menues (nur fuer Warnungen)
   * @param defaultRows Zeilen, wenn nichts oder Ungueltiges steht
   */
  public static MenuLayout parse(String name, ConfigurationSection section, int defaultRows) {
    List<String> warnings = new ArrayList<>();
    if (section == null) {
      return defaults(defaultRows);
    }
    int rows = section.getInt("rows", defaultRows);
    if (rows < 1 || rows > 6) {
      warnings.add(name + ".rows must be 1..6, using " + defaultRows);
      rows = defaultRows;
    }
    int size = rows * 9;
    Map<String, List<Integer>> slots = new LinkedHashMap<>();
    ConfigurationSection slotSection = section.getConfigurationSection("slots");
    if (slotSection != null) {
      for (String key : slotSection.getKeys(false)) {
        Optional<List<Integer>> parsed = parseSlots(slotSection.get(key), size);
        if (parsed.isEmpty()) {
          warnings.add(name + ".slots." + key + " is invalid, using the default");
        } else {
          slots.put(key, parsed.get());
        }
      }
    }
    Map<String, Material> materials = new LinkedHashMap<>();
    ConfigurationSection materialSection = section.getConfigurationSection("materials");
    if (materialSection != null) {
      for (String key : materialSection.getKeys(false)) {
        Material material =
            Material.matchMaterial(
                String.valueOf(materialSection.getString(key, "")).toUpperCase(Locale.ROOT));
        if (material == null || material.isAir() || !material.isItem()) {
          warnings.add(name + ".materials." + key + " is not an item, using the default");
        } else {
          materials.put(key, material);
        }
      }
    }
    Map<String, String> texts = new LinkedHashMap<>();
    ConfigurationSection textSection = section.getConfigurationSection("texts");
    if (textSection != null) {
      for (String key : textSection.getKeys(false)) {
        String value = java.util.Objects.requireNonNullElse(textSection.getString(key, ""), "");
        if (value.length() > 500) {
          warnings.add(name + ".texts." + key + " is longer than 500 characters, ignored");
        } else if (!value.isEmpty()) {
          texts.put(key, value);
        }
      }
    }
    String title = java.util.Objects.requireNonNullElse(section.getString("title", ""), "");
    if (title.length() > 200) {
      warnings.add(name + ".title is longer than 200 characters, ignored");
      title = "";
    }
    return new MenuLayout(rows, title, slots, materials, texts, warnings);
  }

  /**
   * Slot-Angabe: eine Zahl, eine Liste oder ein Bereich wie {@code "0-44"} (auch gemischt in einer
   * Liste). Alle Slots muessen im Inventar liegen.
   */
  static Optional<List<Integer>> parseSlots(Object raw, int size) {
    TreeSet<Integer> out = new TreeSet<>();
    List<Object> parts = new ArrayList<>();
    if (raw instanceof List<?> list) {
      parts.addAll(list);
    } else if (raw != null) {
      parts.add(raw);
    }
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    for (Object part : parts) {
      String text = String.valueOf(part).trim();
      try {
        if (text.matches("[0-9]{1,2}-[0-9]{1,2}")) {
          String[] bounds = text.split("-");
          int from = Integer.parseInt(bounds[0]);
          int to = Integer.parseInt(bounds[1]);
          if (from > to) {
            return Optional.empty();
          }
          for (int i = from; i <= to; i++) {
            out.add(i);
          }
        } else {
          out.add(Integer.parseInt(text));
        }
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    if (out.first() < 0 || out.last() >= size) {
      return Optional.empty();
    }
    return Optional.of(List.copyOf(out));
  }

  /** Slots der Gruppe oder {@code fallback}. */
  public List<Integer> slots(String key, List<Integer> fallback) {
    return slots.getOrDefault(key, fallback);
  }

  /** Erster Slot der Gruppe oder {@code fallback}. */
  public int slot(String key, int fallback) {
    List<Integer> found = slots.get(key);
    return found == null || found.isEmpty() ? fallback : found.get(0);
  }

  public Material material(String key, Material fallback) {
    return materials.getOrDefault(key, fallback);
  }

  /** Eigener Text oder {@code null}, dann gilt der Text der Sprachdatei. */
  public String text(String key) {
    return texts.get(key);
  }
}
