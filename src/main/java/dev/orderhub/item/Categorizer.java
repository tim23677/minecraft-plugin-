package dev.orderhub.item;

import java.util.List;
import java.util.Set;

/**
 * Ordnet ein Item anhand seines Material-Keys einer Marktplatz-Kategorie zu. Rein textbasiert,
 * damit die Zuordnung ohne Server testbar ist und fuer Items beliebiger Plugins (Material-Key
 * {@code minecraft:*}) deterministisch bleibt.
 *
 * @since 2.0.0
 */
public final class Categorizer {

  /** Kategorien in Anzeigereihenfolge. */
  public static final List<String> ALL =
      List.of("ores", "blocks", "farm", "mob-drops", "tools", "potions", "misc");

  /** Kategorien aelterer Versionen, die beim Start neu zugeordnet werden. */
  public static final Set<String> LEGACY = Set.of("blocks", "food", "items");

  private static final Set<String> FARM =
      Set.of(
          "wheat",
          "wheat_seeds",
          "carrot",
          "potato",
          "beetroot",
          "beetroot_seeds",
          "melon_slice",
          "melon_seeds",
          "pumpkin",
          "pumpkin_seeds",
          "sugar_cane",
          "sugar",
          "cactus",
          "bamboo",
          "kelp",
          "dried_kelp",
          "sweet_berries",
          "glow_berries",
          "cocoa_beans",
          "nether_wart",
          "apple",
          "bread",
          "egg",
          "milk_bucket",
          "honey_bottle",
          "honeycomb",
          "mushroom_stem",
          "red_mushroom",
          "brown_mushroom",
          "hay_block",
          "poisonous_potato",
          "baked_potato",
          "cooked_beef",
          "cooked_porkchop",
          "cooked_chicken",
          "cooked_mutton",
          "cooked_rabbit",
          "cooked_cod",
          "cooked_salmon",
          "golden_carrot",
          "torchflower_seeds",
          "pitcher_pod");

  private static final Set<String> MOB_DROPS =
      Set.of(
          "rotten_flesh",
          "bone",
          "bone_meal",
          "string",
          "spider_eye",
          "gunpowder",
          "ender_pearl",
          "blaze_rod",
          "blaze_powder",
          "ghast_tear",
          "slime_ball",
          "magma_cream",
          "phantom_membrane",
          "leather",
          "feather",
          "rabbit_hide",
          "rabbit_foot",
          "ink_sac",
          "glow_ink_sac",
          "prismarine_shard",
          "prismarine_crystals",
          "nautilus_shell",
          "scute",
          "armadillo_scute",
          "shulker_shell",
          "wither_skeleton_skull",
          "nether_star",
          "dragon_breath",
          "beef",
          "porkchop",
          "chicken",
          "mutton",
          "rabbit",
          "cod",
          "salmon",
          "tropical_fish",
          "pufferfish",
          "breeze_rod",
          "wind_charge",
          "trial_key",
          "ominous_trial_key",
          "heart_of_the_sea",
          "echo_shard",
          "totem_of_undying",
          "wolf_armor",
          "turtle_scute");

  private Categorizer() {}

  /**
   * @param itemKey Material-Key wie {@code minecraft:diamond_ore}
   * @return eine Kategorie aus {@link #ALL}
   */
  public static String of(String itemKey) {
    String name = itemKey == null ? "" : itemKey.substring(itemKey.indexOf(':') + 1);
    if (name.isEmpty()) {
      return "misc";
    }
    if (isPotion(name)) {
      return "potions";
    }
    if (isTool(name)) {
      return "tools";
    }
    if (isOre(name)) {
      return "ores";
    }
    if (MOB_DROPS.contains(name) || name.endsWith("_spawn_egg") || name.endsWith("_head")) {
      return "mob-drops";
    }
    if (FARM.contains(name) || name.endsWith("_sapling") || name.endsWith("_seeds")) {
      return "farm";
    }
    if (isBlock(name)) {
      return "blocks";
    }
    return "misc";
  }

  private static boolean isPotion(String n) {
    return n.equals("potion")
        || n.equals("splash_potion")
        || n.equals("lingering_potion")
        || n.equals("tipped_arrow")
        || n.equals("ominous_bottle")
        || n.equals("glass_bottle")
        || n.equals("experience_bottle");
  }

  private static boolean isTool(String n) {
    for (String suffix :
        List.of(
            "_sword",
            "_pickaxe",
            "_axe",
            "_shovel",
            "_hoe",
            "_helmet",
            "_chestplate",
            "_leggings",
            "_boots")) {
      if (n.endsWith(suffix)) {
        return true;
      }
    }
    return Set.of(
            "bow",
            "crossbow",
            "trident",
            "mace",
            "shield",
            "elytra",
            "fishing_rod",
            "shears",
            "flint_and_steel",
            "spyglass",
            "brush",
            "turtle_helmet",
            "arrow",
            "spectral_arrow")
        .contains(n);
  }

  private static boolean isOre(String n) {
    if (n.endsWith("_ore") || n.equals("ancient_debris") || n.startsWith("raw_")) {
      return true;
    }
    return Set.of(
            "coal",
            "charcoal",
            "diamond",
            "emerald",
            "lapis_lazuli",
            "redstone",
            "quartz",
            "amethyst_shard",
            "netherite_scrap",
            "netherite_ingot",
            "iron_ingot",
            "gold_ingot",
            "copper_ingot",
            "iron_nugget",
            "gold_nugget",
            "copper_nugget",
            "glowstone_dust",
            "resin_clump")
        .contains(n);
  }

  private static boolean isBlock(String n) {
    for (String part :
        List.of(
            "stone",
            "dirt",
            "grass_block",
            "sand",
            "gravel",
            "planks",
            "log",
            "wood",
            "brick",
            "glass",
            "wool",
            "concrete",
            "terracotta",
            "deepslate",
            "_block",
            "slab",
            "stairs",
            "wall",
            "fence",
            "obsidian",
            "netherrack",
            "end_stone",
            "podzol",
            "mud",
            "clay",
            "ice",
            "snow",
            "leaves",
            "carpet")) {
      if (n.contains(part)) {
        return true;
      }
    }
    return false;
  }
}
