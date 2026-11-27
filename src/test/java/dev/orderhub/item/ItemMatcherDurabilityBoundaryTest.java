package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.domain.ItemSnapshot;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Grenzfaelle der Haltbarkeits-Untergrenze und der Wertetypen von Item-Pruefung und Richtlinie. Die
 * Tests sind auf ueberlebende Mutanten in {@code ItemMatcher.durabilityAtLeast} und {@code
 * MatchPolicy} zugeschnitten.
 */
class ItemMatcherDurabilityBoundaryTest {

  private ItemMatcher matcher;

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
    matcher =
        new ItemMatcher(
            new ItemCodec(() -> 1),
            MatchPolicy.strict().allowing(RejectionReason.DAMAGED),
            Blocklist.defaults());
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private static ItemStack ironSwordWithDamage(int damage) {
    ItemStack sword = new ItemStack(Material.IRON_SWORD);
    sword.setData(DataComponentTypes.DAMAGE, damage);
    return sword;
  }

  @Test
  void exactlyTheRequiredPercentageOfDurabilityIsEnoughAndOneLessIsNot() {
    // Eisenschwert: 250 Haltbarkeit, 50 % = genau 125 uebrig, ohne Rundung.
    assertThat(Material.IRON_SWORD.getMaxDurability()).isEqualTo((short) 250);
    ItemStack template = new ItemStack(Material.IRON_SWORD);
    Tolerance half = new Tolerance(50, false);
    assertThat(matcher.matches(ironSwordWithDamage(125), template, half)).isTrue();
    assertThat(matcher.matches(ironSwordWithDamage(126), template, half)).isFalse();
  }

  @Test
  void aBrokenItemStillMatchesAMinimumOfZeroButNeverAnOverdamagedOne() {
    ItemStack template = new ItemStack(Material.IRON_SWORD);
    Tolerance any = new Tolerance(0, false);
    // remaining == 0 ist erlaubt, remaining < 0 (mehr Schaden als Haltbarkeit) nie
    assertThat(matcher.matches(ironSwordWithDamage(250), template, any)).isTrue();
    assertThat(matcher.matches(ironSwordWithDamage(251), template, any)).isFalse();
  }

  @Test
  void aBrokenItemDoesNotMatchAPositiveMinimum() {
    ItemStack template = new ItemStack(Material.IRON_SWORD);
    assertThat(matcher.matches(ironSwordWithDamage(250), template, new Tolerance(1, false)))
        .isFalse();
  }

  @Test
  void aMaterialWithoutDurabilityNeverSatisfiesADurabilityMinimum() {
    // Die Vorlage traegt einen Schaden, der Kandidat ist ein Material ohne Haltbarkeit.
    ItemStack candidate = new ItemStack(Material.DIAMOND);
    candidate.setData(DataComponentTypes.DAMAGE, 1);
    ItemStack template = new ItemStack(Material.DIAMOND);
    assertThat(matcher.matches(candidate, template, new Tolerance(0, false))).isFalse();
  }

  @Test
  void prepareGivesNothingForUnreadableTemplatesAndTheMaterialOtherwise() throws Exception {
    ItemCodec codec = new ItemCodec(() -> 1);
    ItemSnapshot good = codec.encode(new ItemStack(Material.IRON_SWORD));
    assertThat(matcher.prepare(good))
        .hasValueSatisfying(t -> assertThat(t.material()).isEqualTo(Material.IRON_SWORD));
    assertThat(matcher.prepare(new ItemSnapshot("minecraft:iron_sword", 1, new byte[] {1, 2, 3})))
        .isEmpty();
  }

  @Test
  void policyCopiesDoNotChangeTheOriginalAndKeepTheOtherSet() {
    MatchPolicy base = MatchPolicy.strict().allowingComponents("minecraft:repair_cost");
    MatchPolicy wider = base.allowing(RejectionReason.LORE);
    assertThat(base.allows(RejectionReason.LORE)).isFalse();
    assertThat(wider.allows(RejectionReason.LORE)).isTrue();
    assertThat(wider.allowedComponents()).containsExactly("minecraft:repair_cost");
    MatchPolicy other = wider.allowingComponents("minecraft:instrument");
    assertThat(wider.allowedComponents()).containsExactly("minecraft:repair_cost");
    assertThat(other.allowedComponents())
        .containsExactlyInAnyOrder("minecraft:repair_cost", "minecraft:instrument");
    assertThat(other.allows(RejectionReason.LORE)).isTrue();
  }

  @Test
  void policyParsingNormalisesComponentKeysAndToleratesNull() {
    MatchPolicy.Parsed parsed =
        MatchPolicy.parse(List.of(" Damaged "), List.of(" MINECRAFT:Repair_Cost ", "instrument"));
    assertThat(parsed.warnings()).isEmpty();
    assertThat(parsed.policy().allows(RejectionReason.DAMAGED)).isTrue();
    assertThat(parsed.policy().allowedComponents())
        .containsExactlyInAnyOrder("minecraft:repair_cost", "minecraft:instrument");
    MatchPolicy.Parsed empty = MatchPolicy.parse(null, null);
    assertThat(empty.warnings()).isEmpty();
    assertThat(empty.policy()).isEqualTo(MatchPolicy.strict());
  }

  @Test
  void aNullCriterionOrComponentEntryIsAWarningNotACrash() {
    List<String> criteria = new java.util.ArrayList<>();
    criteria.add(null);
    List<String> components = new java.util.ArrayList<>();
    components.add(null);
    assertThat(MatchPolicy.parse(criteria, components).warnings()).hasSize(2);
  }

  @Test
  void validationResultsEnforceTheReasonInvariant() {
    assertThat(ValidationResult.ok().valid()).isTrue();
    assertThat(ValidationResult.ok().reason()).isNull();
    assertThat(ValidationResult.rejected(RejectionReason.LORE, "x").detail()).isEqualTo("x");
    assertThat(ValidationResult.rejected(RejectionReason.LORE).valid()).isFalse();
    assertThatThrownBy(() -> new ValidationResult(true, RejectionReason.LORE, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ValidationResult(false, null, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
