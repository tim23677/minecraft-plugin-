package dev.orderhub.discord;

import dev.orderhub.storage.Storage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stellt Discord-Nachrichten in die Outbox. Die Nachricht enthaelt nur Auftrags-Id, Item, Mengen
 * und Betraege, nie Spielernamen, UUIDs oder IP-Adressen. Wenn die Integration aus ist, passiert
 * nichts.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Logger und Storage sind gemeinsam genutzte Infrastruktur")
public final class DiscordService {

  /** Konfigurierbare Ereignisse. */
  public enum Event {
    LARGE_ORDER("large-order"),
    ORDER_COMPLETED("order-completed"),
    ORDER_EXPIRED("order-expired"),
    ORDER_CANCELLED("order-cancelled"),
    CRITICAL_ERROR("critical-error"),
    ADMIN_WARNING("admin-warning"),
    TOP_TRADERS("top-traders");

    private final String key;

    Event(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }
  }

  /** Laufzeitkonfiguration aus {@code discord.yml}. */
  public record Config(
      boolean enabled, String username, Set<Event> events, long largeOrderThreshold) {
    public Config {
      events = Set.copyOf(events);
    }

    public static Config disabled() {
      return new Config(false, "OrderHub", Set.of(), Long.MAX_VALUE);
    }
  }

  private final Storage storage;
  private final Supplier<Config> config;
  private final Logger logger;

  public DiscordService(Storage storage, Supplier<Config> config, Logger logger) {
    this.storage = storage;
    this.config = config;
    this.logger = logger;
  }

  public boolean wants(Event event) {
    Config c = config.get();
    return c.enabled() && c.events().contains(event);
  }

  public long largeOrderThreshold() {
    return config.get().largeOrderThreshold();
  }

  /**
   * Reiht eine Nachricht ein. Blockiert kurz (Datenbank), nie auf einem Minecraft-Thread aufrufen.
   */
  public void publish(Event event, String title, String body) {
    if (!wants(event)) {
      return;
    }
    int color =
        switch (event) {
          case CRITICAL_ERROR -> DiscordMessage.COLOR_ERROR;
          case ADMIN_WARNING, ORDER_EXPIRED, ORDER_CANCELLED -> DiscordMessage.COLOR_WARN;
          default -> DiscordMessage.COLOR_INFO;
        };
    String json = new DiscordMessage(title, body, color).toJson(config.get().username());
    try {
      storage
          .transact(uow -> {
            uow.outbox().enqueueDiscord(json, uow.now());
            return null;
          })
          .join();
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "discord outbox write failed: " + e.getMessage());
    }
  }
}
