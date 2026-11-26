package dev.orderhub;

import dev.orderhub.command.OrderHubCommand;
import dev.orderhub.gui.OrderHubMenuListener;
import dev.orderhub.scheduler.PluginScheduler;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** Einstiegspunkt von OrderHub. Nicht final: MockBukkit erzeugt per ByteBuddy eine Unterklasse. */
public class OrderHubPlugin extends JavaPlugin {

  private PluginScheduler scheduler;

  @Override
  public void onEnable() {
    this.scheduler = PluginScheduler.create(this);
    var command = Objects.requireNonNull(getCommand("orderhub"), "orderhub missing in plugin.yml");
    command.setExecutor(new OrderHubCommand());
    getServer().getPluginManager().registerEvents(new OrderHubMenuListener(), this);
  }

  @Override
  public void onDisable() {
    if (scheduler != null) {
      scheduler.cancelAll();
    }
  }

  public PluginScheduler scheduler() {
    return scheduler;
  }
}
