package dev.orderhub.command;

import dev.orderhub.gui.MainMenu;
import dev.orderhub.gui.MenuManager;
import dev.orderhub.gui.MessageSource;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /orderhub oeffnet das Hauptmenue. */
public final class OrderHubCommand implements CommandExecutor {

  private final MenuManager menus;
  private final MessageSource messages;

  public OrderHubCommand(MenuManager menus, MessageSource messages) {
    this.menus = Objects.requireNonNull(menus, "menus");
    this.messages = Objects.requireNonNull(messages, "messages");
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      @NotNull String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(messages.get("command.player-only"));
      return true;
    }
    menus.open(player, new MainMenu(messages));
    return true;
  }
}
