package dev.orderhub.gui;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Hauptmenue mit Einstiegen in die Funktionen. Ziele ohne Handler werden nicht angezeigt. */
public final class MainMenu extends Menu {

  /** Ziele des Hauptmenues. */
  public enum Target {
    MARKET(10, Material.CHEST, "market"),
    MINE(12, Material.WRITABLE_BOOK, "mine"),
    CREATE(14, Material.EMERALD, "create"),
    CLAIMS(16, Material.ENDER_CHEST, "claims"),
    STATS(20, Material.BOOK, "stats"),
    FAVORITES(22, Material.NETHER_STAR, "favorites"),
    HISTORY(24, Material.CLOCK, "history");

    private final int slot;
    private final Material icon;
    private final String key;

    Target(int slot, Material icon, String key) {
      this.slot = slot;
      this.icon = icon;
      this.key = key;
    }
  }

  public MainMenu(MessageSource messages, Map<Target, Consumer<Player>> handlers) {
    super(messages.get("menu.main-title"), 3, SlotPolicy.none());
    for (Target target : Target.values()) {
      Consumer<Player> handler = handlers.get(target);
      if (handler == null) {
        continue;
      }
      ItemStack icon = new ItemStack(target.icon);
      icon.editMeta(meta -> {
        meta.displayName(messages.get("menu.main." + target.key));
        meta.lore(List.of(messages.get("menu.main." + target.key + "-hint")));
      });
      setButton(
          target.slot,
          Button.of(
              icon,
              context -> {
                context.player().closeInventory();
                handler.accept(context.player());
              }));
    }
  }
}
