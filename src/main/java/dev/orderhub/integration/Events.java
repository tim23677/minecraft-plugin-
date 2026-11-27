package dev.orderhub.integration;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/** Loest OrderHub-Ereignisse aus. Fehler in fremden Handlern stoppen nie den Handelsablauf. */
public final class Events {

  private static final Logger LOG = Logger.getLogger("OrderHub");

  private Events() {}

  public static void fire(Event event) {
    try {
      Bukkit.getPluginManager().callEvent(event);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "event handler failed for " + event.getEventName(), e);
    }
  }
}
