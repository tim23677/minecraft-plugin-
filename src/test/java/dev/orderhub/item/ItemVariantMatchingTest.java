package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BannerPatternLayers;
import io.papermc.paper.datacomponent.item.Fireworks;
import io.papermc.paper.datacomponent.item.OminousBottleAmplifier;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Je Item-Art: Ohne Voreinstellung wird abgelehnt, mit der Voreinstellung ist es bestellbar, und
 * der Abgleich bleibt exakt (anderer Effekt, andere Form oder anderes Muster passt nicht).
 */
class ItemVariantMatchingTest {

  private final ItemCodec codec = new ItemCodec(() -> 1);
  private ItemMatcher strict;
  private ItemMatcher presets;

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
    strict = new ItemMatcher(codec, MatchPolicy.strict(), Blocklist.defaults());
    presets =
        new ItemMatcher(
            codec,
            MatchPolicy.strict()
                .allowingComponents(
                    ComponentPresets.resolve(List.of("all"))
                        .componentKeys()
                        .toArray(String[]::new)),
            Blocklist.defaults());
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  // MockBukkit implementiert equals fuer PotionContents nicht. Gleiche Effekte teilen sich deshalb
  // dieselbe Instanz, auf einem echten Paper-Server vergleicht der Record den Inhalt.
  private static final java.util.Map<PotionType, PotionContents> CONTENTS =
      new java.util.EnumMap<>(PotionType.class);

  private static ItemStack potion(Material material, PotionType type) {
    ItemStack stack = new ItemStack(material);
    stack.setData(
        DataComponentTypes.POTION_CONTENTS,
        CONTENTS.computeIfAbsent(type, t -> PotionContents.potionContents().potion(t).build()));
    return stack;
  }

  private void assertOrderableAndExact(ItemStack wanted, ItemStack same, ItemStack different) {
    assertThat(strict.validateForOrder(wanted).valid()).as("without preset: rejected").isFalse();
    assertThat(presets.validateForOrder(wanted).valid()).as("with preset: orderable").isTrue();
    assertThat(presets.matches(same, wanted, Tolerance.EXACT)).as("same matches").isTrue();
    assertThat(presets.matches(different, wanted, Tolerance.EXACT))
        .as("different does not match")
        .isFalse();
  }

  @Test
  void potion() {
    assertOrderableAndExact(
        potion(Material.POTION, PotionType.STRONG_HEALING),
        potion(Material.POTION, PotionType.STRONG_HEALING),
        potion(Material.POTION, PotionType.HEALING));
  }

  @Test
  void splashPotion() {
    assertOrderableAndExact(
        potion(Material.SPLASH_POTION, PotionType.STRONG_HARMING),
        potion(Material.SPLASH_POTION, PotionType.STRONG_HARMING),
        potion(Material.SPLASH_POTION, PotionType.HARMING));
  }

  @Test
  void lingeringPotion() {
    assertOrderableAndExact(
        potion(Material.LINGERING_POTION, PotionType.REGENERATION),
        potion(Material.LINGERING_POTION, PotionType.REGENERATION),
        potion(Material.LINGERING_POTION, PotionType.LONG_REGENERATION));
  }

  @Test
  void potionOfAnotherMaterialDoesNotMatchEvenWithTheSameEffect() {
    ItemStack wanted = potion(Material.POTION, PotionType.SWIFTNESS);
    assertThat(
            presets.matches(
                potion(Material.SPLASH_POTION, PotionType.SWIFTNESS), wanted, Tolerance.EXACT))
        .isFalse();
  }

  @Test
  void tippedArrow() {
    assertOrderableAndExact(
        potion(Material.TIPPED_ARROW, PotionType.POISON),
        potion(Material.TIPPED_ARROW, PotionType.POISON),
        potion(Material.TIPPED_ARROW, PotionType.SLOWNESS));
  }

  @Test
  void fireworkRocket() {
    ItemStack wanted = rocket(2, Color.RED);
    assertOrderableAndExact(wanted, rocket(2, Color.RED), rocket(3, Color.RED));
    assertThat(presets.matches(rocket(2, Color.BLUE), wanted, Tolerance.EXACT)).isFalse();
  }

  private static ItemStack rocket(int flight, Color color) {
    ItemStack stack = new ItemStack(Material.FIREWORK_ROCKET);
    FireworkEffect explosion =
        FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(color).build();
    stack.setData(
        DataComponentTypes.FIREWORKS,
        Fireworks.fireworks().flightDuration(flight).addEffect(explosion).build());
    return stack;
  }

  @Test
  void goatHorn() {
    assertOrderableAndExact(
        horn(MusicInstrument.PONDER_GOAT_HORN),
        horn(MusicInstrument.PONDER_GOAT_HORN),
        horn(MusicInstrument.SING_GOAT_HORN));
  }

  private static ItemStack horn(MusicInstrument instrument) {
    ItemStack stack = new ItemStack(Material.GOAT_HORN);
    stack.setData(DataComponentTypes.INSTRUMENT, instrument);
    return stack;
  }

  @Test
  void patternedBanner() {
    assertOrderableAndExact(
        banner(PatternType.CREEPER), banner(PatternType.CREEPER), banner(PatternType.SKULL));
  }

  private static final java.util.Map<PatternType, BannerPatternLayers> LAYERS =
      new java.util.HashMap<>();

  private static ItemStack banner(PatternType type) {
    ItemStack stack = new ItemStack(Material.WHITE_BANNER);
    stack.setData(
        DataComponentTypes.BANNER_PATTERNS,
        LAYERS.computeIfAbsent(
            type,
            t ->
                BannerPatternLayers.bannerPatternLayers()
                    .add(new Pattern(DyeColor.RED, t))
                    .build()));
    return stack;
  }

  @Test
  void ominousBottle() {
    assertOrderableAndExact(bottle(2), bottle(2), bottle(4));
  }

  private static ItemStack bottle(int amplifier) {
    ItemStack stack = new ItemStack(Material.OMINOUS_BOTTLE);
    stack.setData(
        DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER, OminousBottleAmplifier.amplifier(amplifier));
    return stack;
  }

  @Test
  void playerHead() {
    assertOrderableAndExact(head("Alice"), head("Alice"), head("Bob"));
  }

  private static final java.util.Map<String, ResolvableProfile> PROFILES =
      new java.util.HashMap<>();

  private static ItemStack head(String name) {
    ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
    stack.setData(
        DataComponentTypes.PROFILE,
        PROFILES.computeIfAbsent(name, n -> ResolvableProfile.resolvableProfile().name(n).build()));
    return stack;
  }
}
