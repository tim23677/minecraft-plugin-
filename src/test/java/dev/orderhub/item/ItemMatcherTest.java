package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.ItemSnapshot;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.WritableBookContent;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Matcher-Tests. Grenze von MockBukkit: Komponenten werden mit {@code setData} gesetzt und gelesen,
 * aber nicht durch {@code serializeAsBytes} getragen. Die geforderte Vorlage (aus dem Snapshot) ist
 * daher in Tests immer ein Item ohne Komponenten. Auch kennt MockBukkit keine Prototyp-Komponenten,
 * "entfernte Standardkomponenten" sind deshalb hier nicht pruefbar.
 */
class ItemMatcherTest {

  private final ItemCodec codec = new ItemCodec(() -> 1);

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private ItemMatcher strict() {
    return new ItemMatcher(codec, MatchPolicy.strict(), Blocklist.defaults());
  }

  private ItemMatcher with(MatchPolicy policy) {
    return new ItemMatcher(codec, policy, Blocklist.defaults());
  }

  private static ItemStack sword() {
    return new ItemStack(Material.DIAMOND_SWORD);
  }

  private ItemSnapshot snapshotOf(Material material) throws ItemCodecException {
    return codec.encode(new ItemStack(material));
  }

  // ---- Standardkriterien ----

  @Test
  void plainItemIsValid() {
    assertThat(strict().validateForOrder(sword()).valid()).isTrue();
    assertThat(strict().validateForOrder(sword()).reason()).isNull();
  }

  @Test
  void nullAndAirAreEmpty() {
    assertThat(strict().validateForOrder(null).reason()).isEqualTo(RejectionReason.EMPTY);
    assertThat(strict().validateForOrder(new ItemStack(Material.AIR)).reason())
        .isEqualTo(RejectionReason.EMPTY);
  }

  @Test
  void rejectsCustomName() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("x"));
    assertThat(strict().validateForOrder(stack).reason()).isEqualTo(RejectionReason.CUSTOM_NAME);
  }

  @Test
  void rejectsLore() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.LORE, ItemLore.lore().addLine(Component.text("l")).build());
    assertThat(strict().validateForOrder(stack).reason()).isEqualTo(RejectionReason.LORE);
  }

  @Test
  void acceptsEmptyLoreComponent() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of()));
    assertThat(strict().validateForOrder(stack).reason()).isNotEqualTo(RejectionReason.LORE);
  }

  @Test
  void rejectsEnchantments() {
    ItemStack stack = sword();
    stack.setData(
        DataComponentTypes.ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(Enchantment.SHARPNESS, 1)));
    assertThat(strict().validateForOrder(stack).reason()).isEqualTo(RejectionReason.ENCHANTED);
  }

  @Test
  void rejectsStoredEnchantmentsOfBooks() {
    ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK);
    stack.setData(
        DataComponentTypes.STORED_ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(Enchantment.MENDING, 1)));
    assertThat(strict().validateForOrder(stack).reason()).isEqualTo(RejectionReason.ENCHANTED);
  }

  @Test
  void rejectsDamage() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.DAMAGE, 1);
    assertThat(strict().validateForOrder(stack).reason()).isEqualTo(RejectionReason.DAMAGED);
  }

  @Test
  void acceptsZeroDamage() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.DAMAGE, 0);
    assertThat(strict().validateForOrder(stack).valid()).isTrue();
  }

  @Test
  void rejectsFilledChestItem() {
    ItemStack chest = new ItemStack(Material.CHEST);
    chest.setData(
        DataComponentTypes.CONTAINER,
        ItemContainerContents.containerContents(List.of(new ItemStack(Material.DIRT))));
    assertThat(strict().validateForOrder(chest).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  @Test
  void acceptsEmptyChestItemWithEmptyContainerComponent() {
    ItemStack chest = new ItemStack(Material.CHEST);
    chest.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(List.of()));
    assertThat(strict().validateForOrder(chest).valid()).isTrue();
  }

  @Test
  void rejectsNestedContainers() {
    ItemStack inner = new ItemStack(Material.BARREL);
    inner.setData(
        DataComponentTypes.CONTAINER,
        ItemContainerContents.containerContents(List.of(new ItemStack(Material.DIAMOND))));
    ItemStack outer = new ItemStack(Material.CHEST);
    outer.setData(
        DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(List.of(inner)));
    assertThat(strict().validateForOrder(outer).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  @Test
  void rejectsContainerNestedInsideContainerEvenIfInnerLooksEmpty() {
    ItemStack outer = new ItemStack(Material.CHEST);
    outer.setData(
        DataComponentTypes.CONTAINER,
        ItemContainerContents.containerContents(List.of(new ItemStack(Material.BARREL))));
    assertThat(strict().validateForOrder(outer).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  @Test
  void rejectsBundleWithContentsWhenBundleIsNotBlocked() {
    ItemMatcher matcher = new ItemMatcher(codec, MatchPolicy.strict(), Blocklist.empty());
    ItemStack bundle = new ItemStack(Material.BUNDLE);
    bundle.setData(
        DataComponentTypes.BUNDLE_CONTENTS,
        BundleContents.bundleContents(List.of(new ItemStack(Material.DIRT))));
    assertThat(matcher.validateForOrder(bundle).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  /**
   * Geschriebene Buecher ({@code WRITTEN_BOOK_CONTENT}) sind unter MockBukkit nicht testbar: der
   * Builder verwirft die Seiten. Der Code-Pfad ist derselbe wie hier.
   */
  @Test
  void rejectsWritableBookWithContent() {
    ItemStack writable = new ItemStack(Material.WRITABLE_BOOK);
    writable.setData(
        DataComponentTypes.WRITABLE_BOOK_CONTENT,
        WritableBookContent.writeableBookContent().addPage("page").build());
    assertThat(strict().validateForOrder(writable).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  @Test
  void rejectsLoadedCrossbow() {
    ItemStack crossbow = new ItemStack(Material.CROSSBOW);
    crossbow.setData(
        DataComponentTypes.CHARGED_PROJECTILES,
        ChargedProjectiles.chargedProjectiles(List.of(new ItemStack(Material.ARROW))));
    assertThat(strict().validateForOrder(crossbow).reason())
        .isEqualTo(RejectionReason.CONTAINER_CONTENTS);
  }

  @Test
  void rejectsUnexpectedComponentAndReportsItsKey() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.REPAIR_COST, 3);
    ValidationResult result = strict().validateForOrder(stack);
    assertThat(result.reason()).isEqualTo(RejectionReason.UNEXPECTED_COMPONENT);
    assertThat(result.detail()).isEqualTo("minecraft:repair_cost");
  }

  @Test
  void rejectsBlockedMaterial() {
    ValidationResult result = strict().validateForOrder(new ItemStack(Material.BEDROCK));
    assertThat(result.reason()).isEqualTo(RejectionReason.BLOCKED_MATERIAL);
    assertThat(result.detail()).isEqualTo("minecraft:bedrock");
  }

  // ---- Ausnahmen je Kriterium ----

  @Test
  void customNameExceptionOnlyExemptsNames() {
    ItemMatcher matcher = with(MatchPolicy.strict().allowing(RejectionReason.CUSTOM_NAME));
    ItemStack named = sword();
    named.setData(DataComponentTypes.CUSTOM_NAME, Component.text("x"));
    ItemStack lored = sword();
    lored.setData(DataComponentTypes.LORE, ItemLore.lore().addLine(Component.text("l")).build());
    assertThat(matcher.validateForOrder(named).valid()).isTrue();
    assertThat(matcher.validateForOrder(lored).reason()).isEqualTo(RejectionReason.LORE);
  }

  @Test
  void loreExceptionExemptsLore() {
    ItemStack lored = sword();
    lored.setData(DataComponentTypes.LORE, ItemLore.lore().addLine(Component.text("l")).build());
    assertThat(
            with(MatchPolicy.strict().allowing(RejectionReason.LORE))
                .validateForOrder(lored)
                .valid())
        .isTrue();
  }

  @Test
  void enchantExceptionExemptsBothEnchantmentKinds() {
    ItemMatcher matcher = with(MatchPolicy.strict().allowing(RejectionReason.ENCHANTED));
    ItemStack sharp = sword();
    sharp.setData(
        DataComponentTypes.ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(Enchantment.SHARPNESS, 1)));
    ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
    book.setData(
        DataComponentTypes.STORED_ENCHANTMENTS,
        ItemEnchantments.itemEnchantments(Map.of(Enchantment.MENDING, 1)));
    assertThat(matcher.validateForOrder(sharp).valid()).isTrue();
    assertThat(matcher.validateForOrder(book).valid()).isTrue();
  }

  @Test
  void damageExceptionExemptsDamage() {
    ItemStack damaged = sword();
    damaged.setData(DataComponentTypes.DAMAGE, 40);
    assertThat(
            with(MatchPolicy.strict().allowing(RejectionReason.DAMAGED))
                .validateForOrder(damaged)
                .valid())
        .isTrue();
  }

  @Test
  void containerExceptionExemptsContents() {
    ItemStack chest = new ItemStack(Material.CHEST);
    chest.setData(
        DataComponentTypes.CONTAINER,
        ItemContainerContents.containerContents(List.of(new ItemStack(Material.DIRT))));
    assertThat(
            with(MatchPolicy.strict().allowing(RejectionReason.CONTAINER_CONTENTS))
                .validateForOrder(chest)
                .valid())
        .isTrue();
  }

  @Test
  void componentKeyExceptionExemptsOnlyThatComponent() {
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.REPAIR_COST, 3);
    ItemMatcher allowed = with(MatchPolicy.strict().allowingComponents("minecraft:repair_cost"));
    assertThat(allowed.validateForOrder(stack).valid()).isTrue();
    stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
    assertThat(allowed.validateForOrder(stack).reason())
        .isEqualTo(RejectionReason.UNEXPECTED_COMPONENT);
  }

  @Test
  void unexpectedExceptionExemptsAnyOtherComponentButNotBlocklist() {
    ItemMatcher matcher = with(MatchPolicy.strict().allowing(RejectionReason.UNEXPECTED_COMPONENT));
    ItemStack stack = sword();
    stack.setData(DataComponentTypes.REPAIR_COST, 3);
    assertThat(matcher.validateForOrder(stack).valid()).isTrue();
    assertThat(matcher.validateForOrder(new ItemStack(Material.BEDROCK)).reason())
        .isEqualTo(RejectionReason.BLOCKED_MATERIAL);
  }

  @Test
  void policyRejectsNonExemptableCriteria() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> MatchPolicy.strict().allowing(RejectionReason.BLOCKED_MATERIAL))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> MatchPolicy.strict().allowing(RejectionReason.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void policyParsingCollectsWarningsInsteadOfThrowing() {
    MatchPolicy.Parsed parsed =
        MatchPolicy.parse(
            List.of("damaged", "LORE", "nonsense", "blocked_material"),
            List.of("minecraft:repair_cost", "Not A Key!!", ""));
    assertThat(parsed.policy().allows(RejectionReason.DAMAGED)).isTrue();
    assertThat(parsed.policy().allows(RejectionReason.LORE)).isTrue();
    assertThat(parsed.policy().allowedComponents()).containsExactly("minecraft:repair_cost");
    assertThat(parsed.warnings()).hasSize(4);
  }

  // ---- matches ----

  @Test
  void matchesSameMaterialIgnoringAmount() throws ItemCodecException {
    ItemStack candidate = new ItemStack(Material.DIAMOND_SWORD, 1);
    assertThat(strict().matches(candidate, snapshotOf(Material.DIAMOND_SWORD))).isTrue();
    assertThat(strict().matches(new ItemStack(Material.DIAMOND, 33), snapshotOf(Material.DIAMOND)))
        .isTrue();
  }

  @Test
  void doesNotMatchOtherMaterial() throws ItemCodecException {
    assertThat(strict().matches(new ItemStack(Material.EMERALD), snapshotOf(Material.DIAMOND)))
        .isFalse();
  }

  @Test
  void doesNotMatchInvalidCandidateEvenIfMaterialEqual() throws ItemCodecException {
    ItemStack named = sword();
    named.setData(DataComponentTypes.CUSTOM_NAME, Component.text("x"));
    assertThat(strict().matches(named, snapshotOf(Material.DIAMOND_SWORD))).isFalse();
  }

  @Test
  void doesNotMatchBlockedMaterialEvenIfRequirementEncodesIt() throws ItemCodecException {
    assertThat(
            strict().matches(new ItemStack(Material.SHULKER_BOX), snapshotOf(Material.SHULKER_BOX)))
        .isFalse();
  }

  @Test
  void doesNotMatchNullOrEmptyCandidate() throws ItemCodecException {
    assertThat(strict().matches(null, snapshotOf(Material.DIAMOND))).isFalse();
    assertThat(strict().matches(new ItemStack(Material.AIR), snapshotOf(Material.DIAMOND)))
        .isFalse();
  }

  @Test
  void doesNotMatchCorruptRequirement() {
    byte[] data = {'O', 'H', 'I', '1', 0, 0, 0, 1, 0, 0, 0, 1, 9, 9, 9};
    ItemSnapshot corrupt = new ItemSnapshot("minecraft:diamond", 1, data);
    assertThat(strict().matches(new ItemStack(Material.DIAMOND), corrupt)).isFalse();
  }

  @Test
  void toleratedDifferencesStillMatchOnMaterial() throws ItemCodecException {
    ItemStack damaged = sword();
    damaged.setData(DataComponentTypes.DAMAGE, 10);
    ItemMatcher matcher = with(MatchPolicy.strict().allowing(RejectionReason.DAMAGED));
    assertThat(matcher.matches(damaged, snapshotOf(Material.DIAMOND_SWORD))).isTrue();
    assertThat(matcher.matches(damaged, snapshotOf(Material.IRON_SWORD))).isFalse();
  }

  @Test
  void untoleratedDifferenceOfOtherComponentDoesNotMatch() throws ItemCodecException {
    ItemStack damagedAndNamed = sword();
    damagedAndNamed.setData(DataComponentTypes.DAMAGE, 10);
    damagedAndNamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("x"));
    ItemMatcher matcher = with(MatchPolicy.strict().allowing(RejectionReason.DAMAGED));
    assertThat(matcher.matches(damagedAndNamed, snapshotOf(Material.DIAMOND_SWORD))).isFalse();
  }
}
