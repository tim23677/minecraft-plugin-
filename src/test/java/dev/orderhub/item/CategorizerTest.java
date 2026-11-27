package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CategorizerTest {

  @Test
  void typicalItemsLandInTheDocumentedCategories() {
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("minecraft:diamond_ore", "ores"),
            Map.entry("minecraft:deepslate_iron_ore", "ores"),
            Map.entry("minecraft:raw_copper", "ores"),
            Map.entry("minecraft:diamond", "ores"),
            Map.entry("minecraft:ancient_debris", "ores"),
            Map.entry("minecraft:oak_planks", "blocks"),
            Map.entry("minecraft:stone_bricks", "blocks"),
            Map.entry("minecraft:iron_block", "blocks"),
            Map.entry("minecraft:wheat", "farm"),
            Map.entry("minecraft:oak_sapling", "farm"),
            Map.entry("minecraft:bread", "farm"),
            Map.entry("minecraft:ender_pearl", "mob-drops"),
            Map.entry("minecraft:zombie_spawn_egg", "mob-drops"),
            Map.entry("minecraft:blaze_rod", "mob-drops"),
            Map.entry("minecraft:diamond_pickaxe", "tools"),
            Map.entry("minecraft:netherite_chestplate", "tools"),
            Map.entry("minecraft:bow", "tools"),
            Map.entry("minecraft:potion", "potions"),
            Map.entry("minecraft:splash_potion", "potions"),
            Map.entry("minecraft:tipped_arrow", "potions"),
            Map.entry("minecraft:name_tag", "misc"),
            Map.entry("minecraft:totem_of_undying", "mob-drops"));
    expected.forEach(
        (key, category) -> assertThat(Categorizer.of(key)).as(key).isEqualTo(category));
  }

  @Test
  void everyResultIsAKnownCategoryAndBadInputFallsBackToMisc() {
    assertThat(Categorizer.of(null)).isEqualTo("misc");
    assertThat(Categorizer.of("")).isEqualTo("misc");
    assertThat(Categorizer.of("minecraft:")).isEqualTo("misc");
    assertThat(Categorizer.of("itemsadder:ruby_ore")).isEqualTo("ores");
    for (String key :
        new String[] {"minecraft:dirt", "x:y", "minecraft:potion", "minecraft:stick", "zzz"}) {
      assertThat(Categorizer.ALL).contains(Categorizer.of(key));
    }
  }

  @Test
  void swordsAreToolsNotOresEvenWithOreWordsInTheName() {
    assertThat(Categorizer.of("minecraft:diamond_sword")).isEqualTo("tools");
    assertThat(Categorizer.of("minecraft:iron_axe")).isEqualTo("tools");
  }
}
