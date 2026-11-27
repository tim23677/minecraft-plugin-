package dev.orderhub.item;

import dev.orderhub.domain.ItemSnapshot;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.WritableBookContent;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/**
 * Serverseitige Item-Pruefung fuer Auftragserstellung und Lieferung. Vertraut keinen Clientangaben:
 * Es zaehlt nur der tatsaechliche {@link ItemStack}.
 *
 * <p>Pruefung ueber die Paper-Datenkomponenten-API ({@code getDataTypes}, {@code
 * isDataOverridden}), nicht ueber ItemMeta. Als "unerwartet" gilt jede Komponente, die vom Prototyp
 * des Materials abweicht und nicht ausdruecklich erlaubt ist, sowie jede entfernte
 * Prototyp-Komponente.
 *
 * <p>Nur im passenden Server-, Region- oder Entity-Kontext aufrufen.
 */
public final class ItemMatcher {

  private static final Set<DataComponentType> CONTAINER_TYPES =
      Set.of(
          DataComponentTypes.CONTAINER,
          DataComponentTypes.BUNDLE_CONTENTS,
          DataComponentTypes.WRITTEN_BOOK_CONTENT,
          DataComponentTypes.WRITABLE_BOOK_CONTENT,
          DataComponentTypes.CHARGED_PROJECTILES);

  private static final Set<DataComponentType> ENCHANT_TYPES =
      Set.of(DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS);

  /** Komponenten, die ein eigenes Kriterium haben und nicht als "unerwartet" gemeldet werden. */
  private static final Set<DataComponentType> HANDLED_TYPES = handledTypes();

  private final ItemCodec codec;
  private final MatchPolicy policy;
  private final Blocklist blocklist;

  public ItemMatcher(ItemCodec codec, MatchPolicy policy, Blocklist blocklist) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.blocklist = Objects.requireNonNull(blocklist, "blocklist");
  }

  private static Set<DataComponentType> handledTypes() {
    Set<DataComponentType> set = new HashSet<>(CONTAINER_TYPES);
    set.addAll(ENCHANT_TYPES);
    set.add(DataComponentTypes.CUSTOM_NAME);
    set.add(DataComponentTypes.LORE);
    set.add(DataComponentTypes.DAMAGE);
    return Set.copyOf(set);
  }

  /** Prueft, ob {@code stack} fuer Auftrag oder Lieferung zugelassen ist. */
  public ValidationResult validateForOrder(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return ValidationResult.rejected(RejectionReason.EMPTY);
    }
    if (blocklist.contains(stack.getType())) {
      return ValidationResult.rejected(
          RejectionReason.BLOCKED_MATERIAL, stack.getType().getKey().toString());
    }
    ValidationResult named = checkNamed(stack);
    if (named != null) {
      return named;
    }
    ValidationResult content = checkContent(stack);
    if (content != null) {
      return content;
    }
    ValidationResult unexpected = checkUnexpected(stack);
    return unexpected != null ? unexpected : ValidationResult.ok();
  }

  private ValidationResult checkNamed(ItemStack stack) {
    if (!policy.allows(RejectionReason.CUSTOM_NAME)
        && stack.hasData(DataComponentTypes.CUSTOM_NAME)) {
      return ValidationResult.rejected(RejectionReason.CUSTOM_NAME);
    }
    ItemLore lore = stack.getData(DataComponentTypes.LORE);
    if (!policy.allows(RejectionReason.LORE) && lore != null && !lore.lines().isEmpty()) {
      return ValidationResult.rejected(RejectionReason.LORE);
    }
    if (!policy.allows(RejectionReason.ENCHANTED)
        && (hasEnchantments(stack.getData(DataComponentTypes.ENCHANTMENTS))
            || hasEnchantments(stack.getData(DataComponentTypes.STORED_ENCHANTMENTS)))) {
      return ValidationResult.rejected(RejectionReason.ENCHANTED);
    }
    Integer damage = stack.getData(DataComponentTypes.DAMAGE);
    if (!policy.allows(RejectionReason.DAMAGED) && damage != null && damage > 0) {
      return ValidationResult.rejected(RejectionReason.DAMAGED);
    }
    return null;
  }

  private ValidationResult checkContent(ItemStack stack) {
    if (policy.allows(RejectionReason.CONTAINER_CONTENTS) || !hasContent(stack)) {
      return null;
    }
    return ValidationResult.rejected(RejectionReason.CONTAINER_CONTENTS);
  }

  private ValidationResult checkUnexpected(ItemStack stack) {
    for (DataComponentType type : overriddenTypes(stack)) {
      if (HANDLED_TYPES.contains(type) || isComponentAllowed(type)) {
        continue;
      }
      if (!policy.allows(RejectionReason.UNEXPECTED_COMPONENT)) {
        return ValidationResult.rejected(
            RejectionReason.UNEXPECTED_COMPONENT, type.getKey().toString());
      }
    }
    if (!policy.allows(RejectionReason.UNEXPECTED_COMPONENT)) {
      ItemStack prototype = new ItemStack(stack.getType());
      for (DataComponentType type : prototype.getDataTypes()) {
        if (!stack.hasData(type) && !isComponentAllowed(type)) {
          return ValidationResult.rejected(
              RejectionReason.UNEXPECTED_COMPONENT, "removed:" + type.getKey());
        }
      }
    }
    return null;
  }

  private boolean isComponentAllowed(DataComponentType type) {
    return policy.allowedComponents().contains(type.getKey().toString());
  }

  private static boolean hasEnchantments(ItemEnchantments enchantments) {
    return enchantments != null && !enchantments.enchantments().isEmpty();
  }

  private static boolean hasContent(ItemStack stack) {
    ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
    if (container != null && anyNonEmpty(container.contents())) {
      return true;
    }
    BundleContents bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
    if (bundle != null && anyNonEmpty(bundle.contents())) {
      return true;
    }
    ChargedProjectiles projectiles = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
    if (projectiles != null && anyNonEmpty(projectiles.projectiles())) {
      return true;
    }
    WrittenBookContent written = stack.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT);
    if (written != null && !written.pages().isEmpty()) {
      return true;
    }
    WritableBookContent writable = stack.getData(DataComponentTypes.WRITABLE_BOOK_CONTENT);
    return writable != null && !writable.pages().isEmpty();
  }

  private static boolean anyNonEmpty(List<ItemStack> stacks) {
    return stacks.stream().anyMatch(s -> s != null && !s.isEmpty());
  }

  private static Set<DataComponentType> overriddenTypes(ItemStack stack) {
    Set<DataComponentType> result = new HashSet<>();
    for (DataComponentType type : stack.getDataTypes()) {
      if (stack.isDataOverridden(type)) {
        result.add(type);
      }
    }
    return result;
  }

  /**
   * Dekodiert die Vorlage einmal. Eine nicht lesbare Vorlage ergibt {@link Optional#empty()}, ein
   * Abgleich mit ihr ist dann nie moeglich.
   */
  public Optional<PreparedTemplate> prepare(ItemSnapshot required) {
    Objects.requireNonNull(required, "required");
    try {
      return Optional.of(new PreparedTemplate(codec.decode(required)));
    } catch (ItemCodecException e) {
      return Optional.empty();
    }
  }

  /**
   * Exakter Vergleich ({@link Tolerance#EXACT}), siehe {@link #matches(ItemStack, ItemSnapshot,
   * Tolerance)}.
   */
  public boolean matches(ItemStack candidate, ItemSnapshot required) {
    return matches(candidate, required, Tolerance.EXACT);
  }

  /** Vergleich mit Toleranz, dekodiert die Vorlage bei jedem Aufruf (fuer Einzelpruefungen). */
  public boolean matches(ItemStack candidate, ItemSnapshot required, Tolerance tolerance) {
    return prepare(required).map(t -> matches(candidate, t, tolerance)).orElse(false);
  }

  /**
   * Vergleich gegen eine bereits dekodierte Vorlage, siehe {@link #matches(ItemStack, ItemStack,
   * Tolerance)}.
   */
  public boolean matches(ItemStack candidate, PreparedTemplate template, Tolerance tolerance) {
    Objects.requireNonNull(template, "template");
    return matches(candidate, template.stack(), tolerance);
  }

  /**
   * Kanonischer Vergleich: {@code candidate} muss selbst zugelassen sein, dasselbe Material wie die
   * Vorlage haben und in jeder Komponente gleich sein. Zulassen (policy) und Tolerieren sind
   * getrennt: Abweichungen sind nur im Rahmen der {@link Tolerance} des Auftrags erlaubt, nie wegen
   * {@code allow-*}. Die Menge bleibt unberuecksichtigt.
   */
  boolean matches(ItemStack candidate, ItemStack template, Tolerance tolerance) {
    Objects.requireNonNull(tolerance, "tolerance");
    if (!validateForOrder(candidate).valid()) {
      return false;
    }
    if (template.getType() != candidate.getType()) {
      return false;
    }
    Set<DataComponentType> types = overriddenTypes(candidate);
    types.addAll(overriddenTypes(template));
    for (DataComponentType type : types) {
      if (!componentMatches(candidate, template, type, tolerance)) {
        return false;
      }
    }
    return true;
  }

  private static boolean componentMatches(
      ItemStack candidate, ItemStack template, DataComponentType type, Tolerance tolerance) {
    if (type == DataComponentTypes.DAMAGE && tolerance.minDurabilityPercent() < 100) {
      return durabilityAtLeast(candidate, tolerance.minDurabilityPercent());
    }
    if (tolerance.enchantmentsAtLeast() && ENCHANT_TYPES.contains(type)) {
      return enchantmentsAtLeast(candidate, template, type);
    }
    return sameComponent(candidate, template, type);
  }

  private static boolean durabilityAtLeast(ItemStack candidate, int minPercent) {
    Integer maxData = candidate.getData(DataComponentTypes.MAX_DAMAGE);
    int max = maxData != null ? maxData : candidate.getType().getMaxDurability();
    if (max <= 0) {
      return false;
    }
    Integer damage = candidate.getData(DataComponentTypes.DAMAGE);
    int remaining = max - (damage == null ? 0 : damage);
    // remaining/max >= minPercent/100, ganzzahlig ohne Rundungsfehler
    return remaining >= 0 && (long) remaining * 100 >= (long) minPercent * max;
  }

  private static boolean enchantmentsAtLeast(
      ItemStack candidate, ItemStack template, DataComponentType type) {
    if (!(type instanceof DataComponentType.Valued<?> valued)) {
      return false;
    }
    Object wanted = template.getData(valued);
    Object offered = candidate.getData(valued);
    Map<Enchantment, Integer> required = enchantmentsOf(wanted);
    Map<Enchantment, Integer> present = enchantmentsOf(offered);
    for (Map.Entry<Enchantment, Integer> entry : required.entrySet()) {
      Integer level = present.get(entry.getKey());
      if (level == null || level < entry.getValue()) {
        return false;
      }
    }
    return true;
  }

  private static Map<Enchantment, Integer> enchantmentsOf(Object data) {
    if (data instanceof ItemEnchantments enchantments) {
      return enchantments.enchantments();
    }
    return Map.of();
  }

  private static boolean sameComponent(ItemStack a, ItemStack b, DataComponentType type) {
    if (a.isDataOverridden(type) != b.isDataOverridden(type)) {
      return false;
    }
    if (type instanceof DataComponentType.Valued<?> valued) {
      return Objects.equals(a.getData(valued), b.getData(valued));
    }
    return a.hasData(type) == b.hasData(type);
  }
}
