package dev.orderhub;

import dev.orderhub.command.OrderHubCommand;
import dev.orderhub.gui.MenuConfig;
import dev.orderhub.gui.MenuListener;
import dev.orderhub.gui.MenuManager;
import dev.orderhub.gui.MessageSource;
import dev.orderhub.gui.ReturnSink;
import dev.orderhub.scheduler.PluginScheduler;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/** Einstiegspunkt von OrderHub. Nicht final: MockBukkit erzeugt per ByteBuddy eine Unterklasse. */
public class OrderHubPlugin extends JavaPlugin {

  private PluginScheduler scheduler;
  private MenuManager menus;

  @Override
  public void onEnable() {
    this.scheduler = PluginScheduler.create(this);
    // Platzhalter bis zur persistenten Rueckgabewarteschlange. Das Hauptmenue hat keine
    // Eingabeslots, die Senke wird daher noch nicht erreicht.
    ReturnSink sink =
        (playerId, items) ->
            getLogger()
                .log(
                    Level.SEVERE,
                    "no persistent return queue yet, {0} stacks for {1}",
                    new Object[] {items.size(), playerId});
    this.menus = new MenuManager(getLogger(), scheduler, sink, MenuConfig.defaults());
    MessageSource messages = MessageSource.keyEcho();
    var command = Objects.requireNonNull(getCommand("orderhub"), "orderhub missing in plugin.yml");
    command.setExecutor(new OrderHubCommand(menus, messages));
    getServer().getPluginManager().registerEvents(new MenuListener(menus, getLogger()), this);
  }

  @Override
  public void onDisable() {
    if (menus != null) {
      menus.closeAll();
    }
    if (scheduler != null) {
      scheduler.cancelAll();
    }
  }

  public PluginScheduler scheduler() {
    return scheduler;
  }

  public MenuManager menus() {
    return menus;
  }
}
