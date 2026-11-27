package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Zulassen und Tolerieren sind getrennt: {@code allow-*} bestimmt nur, was in einem Auftrag
 * vorkommen darf. Beim Abgleich wird exakt verglichen, ausser der Auftrag traegt eine
 * ausdrueckliche {@link Tolerance} (Untergrenzen statt Ignorieren). Die Vorlage wird hier direkt
 * als ItemStack uebergeben, weil MockBukkit Komponenten nicht durch die Serialisierung traegt.
 */
class ItemMatcherToleranceTest {

  private static final MatchPolicy ALLOW_ALL =
      MatchPolicy.strict().allowing(RejectionReason.DAMAGED, RejectionReason.ENCHANTED);

  private final ItemCodec codec = new ItemCodec(() -> 1);
  private final ItemMatcher matcher = new ItemMatcher(codec, ALLOW_ALL, Blocklist.defaults());

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private static ItemStack swordWithDamage(int damage) {
    ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
    sword.setData(DataComponentTypes.DAMAGE, damage);
    return sword;
  }

  private static ItemStack book(Enchantment enchantment, int level) {
    ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
    book.setData(
        DataComponentTypes.STORED_ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(enchantment, level)));
    return book;
  }

  private static ItemStack tool(Enchantment enchantment, int level) {
    ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
    sword.setData(
        DataComponentTypes.ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(enchantment, level)));
    return sword;
  }

  // ---- Zulassen heisst nicht tolerieren ----

  @Test
  void admittedDamageIsNotToleratedWhenTheOrderIsExact() {
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    assertThat(matcher.validateForOrder(swordWithDamage(1000)).valid()).isTrue();
    assertThat(matcher.matches(swordWithDamage(1000), template, Tolerance.EXACT)).isFalse();
    assertThat(matcher.matches(new ItemStack(Material.DIAMOND_SWORD), template, Tolerance.EXACT))
        .isTrue();
  }

  @Test
  void admittedEnchantmentIsNotToleratedWhenTheOrderIsExact() {
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 1), template, Tolerance.EXACT))
        .isFalse();
  }

  @Test
  void exactOrderForADamagedItemNeedsTheSameDamage() {
    assertThat(matcher.matches(swordWithDamage(100), swordWithDamage(100), Tolerance.EXACT))
        .isTrue();
    assertThat(matcher.matches(swordWithDamage(101), swordWithDamage(100), Tolerance.EXACT))
        .isFalse();
  }

  // ---- Haltbarkeit: Untergrenze statt Ignorieren ----

  @Test
  void newSwordMatchesAndAlmostBrokenSwordDoesNotWhenAMinimumDurabilityIsSet() {
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    int max = Material.DIAMOND_SWORD.getMaxDurability();
    Tolerance atLeast50 = new Tolerance(50, false);
    assertThat(matcher.matches(new ItemStack(Material.DIAMOND_SWORD), template, atLeast50))
        .isTrue();
    assertThat(matcher.matches(swordWithDamage(max / 4), template, atLeast50)).isTrue();
    assertThat(matcher.matches(swordWithDamage(max - 5), template, atLeast50)).isFalse();
  }

  @Test
  void durabilityBoundaryIsInclusive() {
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    int max = Material.DIAMOND_SWORD.getMaxDurability();
    Tolerance atLeast50 = new Tolerance(50, false);
    int damageAtExactlyHalf = max - (max * 50 + 99) / 100; // remaining >= 50 %
    assertThat(matcher.matches(swordWithDamage(damageAtExactlyHalf), template, atLeast50)).isTrue();
    assertThat(matcher.matches(swordWithDamage(damageAtExactlyHalf + 2), template, atLeast50))
        .isFalse();
  }

  @Test
  void durabilityMinimumOfZeroAcceptsAnyDamageButNotAnotherMaterial() {
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    Tolerance any = new Tolerance(0, false);
    assertThat(
            matcher.matches(
                swordWithDamage(Material.DIAMOND_SWORD.getMaxDurability() - 1), template, any))
        .isTrue();
    assertThat(matcher.matches(new ItemStack(Material.IRON_SWORD), template, any)).isFalse();
  }

  // ---- Verzauberungen: Mindeststufen ----

  @Test
  void sameEnchantmentWithHigherLevelMatchesAMinimumLevelOrder() {
    Tolerance atLeast = new Tolerance(100, true);
    ItemStack template = tool(Enchantment.SHARPNESS, 3);
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 5), template, atLeast)).isTrue();
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 3), template, atLeast)).isTrue();
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 2), template, atLeast)).isFalse();
  }

  @Test
  void higherLevelDoesNotMatchAnExactOrder() {
    ItemStack template = tool(Enchantment.SHARPNESS, 3);
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 5), template, Tolerance.EXACT))
        .isFalse();
    assertThat(matcher.matches(tool(Enchantment.SHARPNESS, 3), template, Tolerance.EXACT)).isTrue();
  }

  @Test
  void mendingBookDoesNotMatchAnUnbreakingOneBookInEitherMode() {
    ItemStack wantedMending = book(Enchantment.MENDING, 1);
    ItemStack offeredUnbreaking = book(Enchantment.UNBREAKING, 1);
    assertThat(matcher.matches(offeredUnbreaking, wantedMending, Tolerance.EXACT)).isFalse();
    assertThat(matcher.matches(offeredUnbreaking, wantedMending, new Tolerance(100, true)))
        .isFalse();
    assertThat(matcher.matches(book(Enchantment.MENDING, 1), wantedMending, Tolerance.EXACT))
        .isTrue();
  }

  @Test
  void aMissingEnchantmentNeverMatchesEvenWithMinimumLevels() {
    ItemStack template = tool(Enchantment.SHARPNESS, 1);
    assertThat(
            matcher.matches(
                new ItemStack(Material.DIAMOND_SWORD), template, new Tolerance(100, true)))
        .isFalse();
  }

  @Test
  void toleranceNeverRelaxesOtherComponents() {
    ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
    named.setData(DataComponentTypes.CUSTOM_NAME, net.kyori.adventure.text.Component.text("x"));
    ItemMatcher withNames =
        new ItemMatcher(
            codec, ALLOW_ALL.allowing(RejectionReason.CUSTOM_NAME), Blocklist.defaults());
    ItemStack template = new ItemStack(Material.DIAMOND_SWORD);
    assertThat(withNames.matches(named, template, new Tolerance(0, true))).isFalse();
  }

  // ---- Wertebereich ----

  @Test
  void toleranceRejectsNonsenseValues() {
    assertThatThrownBy(() -> new Tolerance(-1, false)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Tolerance(101, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Tolerance.EXACT.minDurabilityPercent()).isEqualTo(100);
    assertThat(Tolerance.EXACT.enchantmentsAtLeast()).isFalse();
  }
}
