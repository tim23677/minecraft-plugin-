package dev.orderhub.bootstrap;

import dev.orderhub.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Unveraenderlicher, validierter Schnappschuss von {@code config.yml}. Ungueltige Werte fallen auf
 * den Standard zurueck und erzeugen eine Warnung, nie eine Exception.
 */
public record Settings(
    String serverId,
    String language,
    boolean debug,
    long minUnitPrice,
    long maxUnitPrice,
    int maxQuantity,
    int defaultMaxOpenOrders,
    int expiryHours,
    String cancelWord,
    int inputTimeoutSeconds,
    int createCooldownSeconds,
    int createFeeBp,
    int salesTaxBp,
    boolean allowCustomName,
    boolean allowLore,
    boolean allowEnchanted,
    boolean allowDamaged,
    List<String> blockedMaterials,
    List<String> blockedTags,
    int clickDebounceMs,
    int cacheMaxEntries,
    int syncIntervalSeconds,
    int payoutWorkerIntervalSeconds,
    int payoutMaxAttempts,
    int minTradesForRecommendation,
    int recommendationWindowDays,
    int notificationCooldownSeconds,
    int notificationBatchWindowSeconds,
    boolean reputationEnabled,
    int historyRetentionDays,
    int cleanupIntervalMinutes,
    boolean soundsEnabled,
    String soundSuccess,
    String soundError,
    String soundClick,
    List<String> aliases,
    List<String> warnings) {

  public Settings {
    blockedMaterials = List.copyOf(blockedMaterials);
    blockedTags = List.copyOf(blockedTags);
    aliases = List.copyOf(aliases);
    warnings = List.copyOf(warnings);
  }

  private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9-]{1,32}");

  /** Liest die Konfiguration. {@code cfg} darf unvollstaendig sein. */
  public static Settings from(ConfigurationSection cfg) {
    List<String> warnings = new ArrayList<>();
    String serverId = str(cfg, "server-id", "main");
    if (!SERVER_ID.matcher(serverId).matches()) {
      warnings.add("server-id '" + serverId + "' is invalid, using 'main'");
      serverId = "main";
    }
    String language = str(cfg, "language", "en").toLowerCase(java.util.Locale.ROOT);
    long minPrice = price(cfg, "orders.min-unit-price", "0.01", warnings);
    long maxPrice = price(cfg, "orders.max-unit-price", "1000000", warnings);
    if (minPrice <= 0 || maxPrice < minPrice) {
      warnings.add("price limits invalid, using defaults");
      minPrice = 1;
      maxPrice = Money.parse("1000000");
    }
    return new Settings(
        serverId,
        language,
        cfg.getBoolean("debug", false),
        minPrice,
        maxPrice,
        range(cfg, "orders.max-quantity", 2304, 1, 1_000_000, warnings),
        range(cfg, "orders.default-max-open-orders", 3, 1, 1000, warnings),
        range(cfg, "orders.expiry-hours", 168, 1, 24 * 365, warnings),
        str(cfg, "orders.cancel-word", "cancel"),
        range(cfg, "orders.input-timeout-seconds", 60, 5, 600, warnings),
        range(cfg, "orders.create-cooldown-seconds", 5, 0, 3600, warnings),
        range(cfg, "fees.create-fee-bp", 100, 0, 10_000, warnings),
        range(cfg, "fees.sales-tax-bp", 200, 0, 10_000, warnings),
        cfg.getBoolean("items.allow-custom-name", false),
        cfg.getBoolean("items.allow-lore", false),
        cfg.getBoolean("items.allow-enchanted", false),
        cfg.getBoolean("items.allow-damaged", false),
        List.copyOf(cfg.getStringList("items.blocked-materials")),
        List.copyOf(cfg.getStringList("items.blocked-tags")),
        range(cfg, "delivery.click-debounce-ms", 150, 0, 5000, warnings),
        range(cfg, "cache.max-entries", 5000, 100, 1_000_000, warnings),
        range(cfg, "cache.sync-interval-seconds", 3, 1, 600, warnings),
        range(cfg, "payouts.worker-interval-seconds", 2, 1, 600, warnings),
        range(cfg, "payouts.max-attempts", 5, 1, 100, warnings),
        range(cfg, "market.min-trades-for-recommendation", 5, 1, 10_000, warnings),
        range(cfg, "market.recommendation-window-days", 7, 1, 90, warnings),
        range(cfg, "notifications.cooldown-seconds", 60, 0, 86_400, warnings),
        range(cfg, "notifications.batch-window-seconds", 10, 0, 3600, warnings),
        cfg.getBoolean("reputation.enabled", false),
        range(cfg, "retention.history-days", 120, 1, 3650, warnings),
        range(cfg, "retention.cleanup-interval-minutes", 30, 1, 1440, warnings),
        cfg.getBoolean("sounds.enabled", true),
        str(cfg, "sounds.success", "entity.experience_orb.pickup"),
        str(cfg, "sounds.error", "block.note_block.bass"),
        str(cfg, "sounds.click", "ui.button.click"),
        List.copyOf(cfg.getStringList("commands.aliases")),
        List.copyOf(warnings));
  }

  private static String str(ConfigurationSection cfg, String path, String def) {
    return Objects.requireNonNullElse(cfg.getString(path, def), def);
  }

  private static long price(ConfigurationSection cfg, String path, String def, List<String> w) {
    String raw = String.valueOf(cfg.get(path, def));
    try {
      return Money.parse(raw);
    } catch (NumberFormatException e) {
      w.add(path + " '" + raw + "' is invalid, using " + def);
      return Money.parse(def);
    }
  }

  private static int range(
      ConfigurationSection cfg, String path, int def, int min, int max, List<String> w) {
    int value = cfg.getInt(path, def);
    if (value < min || value > max) {
      w.add(path + "=" + value + " out of range " + min + ".." + max + ", using " + def);
      return def;
    }
    return value;
  }
}
