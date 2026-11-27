package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class BlocklistTest {

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void defaultsContainAllSingleItems() {
    Blocklist list = Blocklist.defaults();
    List<Material> expected =
        List.of(
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART,
            Material.BEDROCK,
            Material.BARRIER,
            Material.STRUCTURE_BLOCK,
            Material.JIGSAW,
            Material.DEBUG_STICK,
            Material.LIGHT,
            Material.KNOWLEDGE_BOOK,
            Material.SPAWNER,
            Material.TRIAL_SPAWNER,
            Material.VAULT,
            Material.REINFORCED_DEEPSLATE,
            Material.END_PORTAL_FRAME,
            Material.PETRIFIED_OAK_SLAB,
            Material.BUNDLE);
    for (Material material : expected) {
      assertThat(list.contains(material)).as(material.name()).isTrue();
    }
  }

  @Test
  void defaultsContainEveryShulkerBox() {
    Blocklist list = Blocklist.defaults();
    assertThat(Tag.SHULKER_BOXES.getValues()).hasSize(17);
    for (Material material : Tag.SHULKER_BOXES.getValues()) {
      assertThat(list.contains(material)).as(material.name()).isTrue();
    }
  }

  @Test
  void defaultsContainEveryBundleColour() {
    Blocklist list = Blocklist.defaults();
    List<Material> bundles =
        java.util.Arrays.stream(Material.values())
            .filter(m -> m.name().endsWith("BUNDLE") && !m.name().startsWith("LEGACY_"))
            .toList();
    assertThat(bundles).hasSizeGreaterThanOrEqualTo(17);
    for (Material material : bundles) {
      assertThat(list.contains(material)).as(material.name()).isTrue();
    }
  }

  @Test
  void defaultsContainEverySpawnEgg() {
    Blocklist list = Blocklist.defaults();
    List<Material> eggs =
        java.util.Arrays.stream(Material.values())
            .filter(m -> m.name().endsWith("_SPAWN_EGG") && !m.name().startsWith("LEGACY_"))
            .toList();
    assertThat(eggs).isNotEmpty();
    for (Material material : eggs) {
      assertThat(list.contains(material)).as(material.name()).isTrue();
    }
  }

  @Test
  void defaultsDoNotBlockOrdinaryItems() {
    Blocklist list = Blocklist.defaults();
    assertThat(list.contains(Material.DIAMOND)).isFalse();
    assertThat(list.contains(Material.CHEST)).isFalse();
    assertThat(list.contains(Material.STONE)).isFalse();
    assertThat(list.contains(Material.PETRIFIED_OAK_SLAB)).isTrue();
    assertThat(list.contains(Material.OAK_SLAB)).isFalse();
  }

  @Test
  void parseAddsMaterialsAndTagsOnTopOfDefaults() {
    Blocklist.Parsed parsed =
        Blocklist.parse(List.of("minecraft:diamond", "emerald", "#minecraft:logs"), true);
    assertThat(parsed.warnings()).isEmpty();
    assertThat(parsed.blocklist().contains(Material.DIAMOND)).isTrue();
    assertThat(parsed.blocklist().contains(Material.EMERALD)).isTrue();
    assertThat(parsed.blocklist().contains(Material.OAK_LOG)).isTrue();
    assertThat(parsed.blocklist().contains(Material.BEDROCK)).isTrue();
  }

  @Test
  void parseWithoutDefaultsStartsEmpty() {
    Blocklist.Parsed parsed = Blocklist.parse(List.of("diamond"), false);
    assertThat(parsed.blocklist().contains(Material.DIAMOND)).isTrue();
    assertThat(parsed.blocklist().contains(Material.BEDROCK)).isFalse();
  }

  @Test
  void parseCollectsWarningsForBadEntriesWithoutThrowing() {
    java.util.List<String> entries = new java.util.ArrayList<>();
    entries.add("minecraft:no_such_item");
    entries.add("Not A Key!!");
    entries.add("");
    entries.add(null);
    entries.add("#minecraft:no_such_tag");
    entries.add("#Bad Tag!!");
    entries.add("minecraft:stone");
    Blocklist.Parsed parsed = Blocklist.parse(entries, false);
    assertThat(parsed.warnings()).hasSize(6);
    assertThat(parsed.blocklist().materials()).containsExactly(Material.STONE);
  }

  @Test
  void parseHandlesNullListAndKeepsDefaults() {
    Blocklist.Parsed parsed = Blocklist.parse(null, true);
    assertThat(parsed.warnings()).isEmpty();
    assertThat(parsed.blocklist().contains(Material.BEDROCK)).isTrue();
  }

  @Test
  void emptyBlocksNothing() {
    assertThat(Blocklist.empty().materials()).isEmpty();
  }
}
