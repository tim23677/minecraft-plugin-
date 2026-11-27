package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentPresetsTest {

  @Test
  void everyNamedPresetResolvesToAtLeastOneValidKey() {
    for (String name : ComponentPresets.names()) {
      var resolved = ComponentPresets.resolve(List.of(name));
      assertThat(resolved.warnings()).as(name).isEmpty();
      assertThat(resolved.componentKeys()).as(name).isNotEmpty();
      assertThat(MatchPolicy.parse(List.of(), resolved.componentKeys()).warnings())
          .as(name)
          .isEmpty();
    }
  }

  @Test
  void potionsPresetCoversPotionContents() {
    assertThat(ComponentPresets.resolve(List.of("potions")).componentKeys())
        .contains("minecraft:potion_contents");
  }

  @Test
  void presetsCoverTheDocumentedItemKinds() {
    assertThat(ComponentPresets.resolve(List.of("all")).componentKeys())
        .contains(
            "minecraft:potion_contents",
            "minecraft:fireworks",
            "minecraft:instrument",
            "minecraft:banner_patterns",
            "minecraft:ominous_bottle_amplifier",
            "minecraft:profile");
  }

  @Test
  void unknownNamesAreWarningsNotExceptionsAndNamesAreCaseInsensitive() {
    var resolved = ComponentPresets.resolve(List.of("Potions", "nope", " banners "));
    assertThat(resolved.componentKeys())
        .contains("minecraft:potion_contents", "minecraft:banner_patterns");
    assertThat(resolved.warnings()).containsExactly("unknown component preset: nope");
  }

  @Test
  void nullAndEmptyInputGiveNothing() {
    assertThat(ComponentPresets.resolve(null).componentKeys()).isEmpty();
    assertThat(ComponentPresets.resolve(List.of()).componentKeys()).isEmpty();
  }
}
