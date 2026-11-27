package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class MenuLayoutTest {

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private static YamlConfiguration yaml(String text) throws Exception {
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.loadFromString(text);
    return cfg;
  }

  @Test
  void readsRowsTitleSlotsMaterialsAndTexts() throws Exception {
    var cfg =
        yaml(
            "market:\n  rows: 5\n  title: '<gold>Shop'\n  slots:\n    content: '0-35'\n    next: 44\n    extra: [1, 3, '5-6']\n"
                + "  materials:\n    next: spectral_arrow\n  texts:\n    sort: '<red>Sorted'\n");
    var layout = MenuLayout.parse("market", cfg.getConfigurationSection("market"), 6);
    assertThat(layout.warnings()).isEmpty();
    assertThat(layout.rows()).isEqualTo(5);
    assertThat(layout.title()).isEqualTo("<gold>Shop");
    assertThat(layout.slots("content", List.of())).hasSize(36).startsWith(0).endsWith(35);
    assertThat(layout.slot("next", 53)).isEqualTo(44);
    assertThat(layout.slots("extra", List.of())).containsExactly(1, 3, 5, 6);
    assertThat(layout.material("next", Material.ARROW)).isEqualTo(Material.SPECTRAL_ARROW);
    assertThat(layout.text("sort")).isEqualTo("<red>Sorted");
    assertThat(layout.text("missing")).isNull();
  }

  @Test
  void missingSectionGivesTheDefaultsWithoutWarnings() {
    var layout = MenuLayout.parse("x", null, 4);
    assertThat(layout.rows()).isEqualTo(4);
    assertThat(layout.warnings()).isEmpty();
    assertThat(layout.slot("next", 53)).isEqualTo(53);
    assertThat(layout.material("next", Material.ARROW)).isEqualTo(Material.ARROW);
  }

  @Test
  void invalidValuesFallBackAndWarnInsteadOfThrowing() throws Exception {
    var cfg =
        yaml(
            "m:\n  rows: 9\n  title: ''\n  slots:\n    a: 99\n    b: 'x-y'\n    c: '9-3'\n    d: [1, 'z']\n"
                + "  materials:\n    a: not_a_material\n    b: air\n");
    var layout = MenuLayout.parse("m", cfg.getConfigurationSection("m"), 6);
    assertThat(layout.rows()).isEqualTo(6);
    assertThat(layout.slots()).isEmpty();
    assertThat(layout.materials()).isEmpty();
    assertThat(layout.warnings()).hasSize(7);
  }

  @Test
  void slotsMustFitTheInventory() throws Exception {
    var cfg = yaml("m:\n  rows: 3\n  slots:\n    ok: 26\n    bad: 27\n");
    var layout = MenuLayout.parse("m", cfg.getConfigurationSection("m"), 3);
    assertThat(layout.slot("ok", 0)).isEqualTo(26);
    assertThat(layout.slots()).doesNotContainKey("bad");
    assertThat(layout.warnings()).hasSize(1);
  }

  @Test
  void overlongTextsAreIgnored() throws Exception {
    var cfg =
        yaml(
            "m:\n  title: '" + "x".repeat(201) + "'\n  texts:\n    a: '" + "y".repeat(501) + "'\n");
    var layout = MenuLayout.parse("m", cfg.getConfigurationSection("m"), 6);
    assertThat(layout.title()).isEmpty();
    assertThat(layout.text("a")).isNull();
    assertThat(layout.warnings()).hasSize(2);
  }

  @Test
  void rowsOutsideOneToSixAreRejectedByTheRecord() {
    assertThatThrownBy(() -> MenuLayout.defaults(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MenuLayout.defaults(7)).isInstanceOf(IllegalArgumentException.class);
  }
}
