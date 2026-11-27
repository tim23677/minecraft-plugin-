package dev.orderhub.gui;

import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.inventory.ItemStack;

/** Ein Menue-Element: Icon (Supplier, wird bei jedem Rendern neu gelesen) plus Klick-Handler. */
public final class Button {

  /** Reagiert auf einen validierten Klick im Entity-Kontext des Spielers. */
  @FunctionalInterface
  public interface ClickHandler {
    void onClick(ClickContext context);
  }

  private static final ClickHandler NOOP = context -> {};

  private final Supplier<ItemStack> icon;
  private final ClickHandler handler;

  private Button(Supplier<ItemStack> icon, ClickHandler handler) {
    this.icon = Objects.requireNonNull(icon, "icon");
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  public static Button of(Supplier<ItemStack> icon, ClickHandler handler) {
    return new Button(icon, handler);
  }

  public static Button of(ItemStack icon, ClickHandler handler) {
    ItemStack copy = icon.clone();
    return new Button(copy::clone, handler);
  }

  /** Reine Anzeige ohne Aktion. */
  public static Button decoration(ItemStack icon) {
    return of(icon, NOOP);
  }

  /** Aktuelles Icon als Kopie. */
  public ItemStack render() {
    ItemStack item = icon.get();
    return item == null ? new ItemStack(org.bukkit.Material.AIR) : item.clone();
  }

  public ClickHandler handler() {
    return handler;
  }
}
