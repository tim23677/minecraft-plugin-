package dev.orderhub.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Hauptmenue (Platzhalter bis zu den eigentlichen Menues). Texte nur ueber Message-Keys. */
public final class MainMenu extends Menu {

  public static final int REFRESH_SLOT = 13;

  public MainMenu(MessageSource messages) {
    super(messages.get("menu.main.title"), 3, SlotPolicy.none());
    ItemStack icon = new ItemStack(Material.PAPER);
    icon.editMeta(meta -> meta.displayName(messages.get("menu.main.refresh")));
    setButton(
        REFRESH_SLOT,
        Button.of(
            icon, context -> context.player().sendMessage(messages.get("menu.main.refreshed"))));
  }
}
