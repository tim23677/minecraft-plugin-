package dev.orderhub;

import dev.orderhub.bootstrap.ConfigUpdater;
import dev.orderhub.bootstrap.DatabaseSetup;
import dev.orderhub.bootstrap.Messages;
import dev.orderhub.bootstrap.Settings;
import dev.orderhub.command.OrderAdminCommand;
import dev.orderhub.command.OrderCommand;
import dev.orderhub.config.Expiry;
import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.discord.DiscordService;
import dev.orderhub.discord.DiscordWorker;
import dev.orderhub.discord.WebhookSender;
import dev.orderhub.discord.WebhookUrl;
import dev.orderhub.economy.EconomyBridge;
import dev.orderhub.economy.UnavailableEconomy;
import dev.orderhub.economy.vault.VaultHook;
import dev.orderhub.flow.ChatInput;
import dev.orderhub.flow.CreateFlow;
import dev.orderhub.flow.DeliveryFlow;
import dev.orderhub.flow.InsightsFlow;
import dev.orderhub.flow.JoinListener;
import dev.orderhub.flow.Menus;
import dev.orderhub.flow.ParcelFlow;
import dev.orderhub.flow.ReturnQueue;
import dev.orderhub.flow.Services;
import dev.orderhub.gui.MenuConfig;
import dev.orderhub.gui.MenuListener;
import dev.orderhub.gui.MenuManager;
import dev.orderhub.integration.ConsoleAudit;
import dev.orderhub.item.Blocklist;
import dev.orderhub.item.ItemCodec;
import dev.orderhub.item.ItemMatcher;
import dev.orderhub.item.MatchPolicy;
import dev.orderhub.item.RejectionReason;
import dev.orderhub.scheduler.PluginScheduler;
import dev.orderhub.service.AdminService;
import dev.orderhub.service.InsightsService;
import dev.orderhub.service.OrderService;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.tx.AuditSink;
import dev.orderhub.tx.ClaimEngine;
import dev.orderhub.tx.DeliveryEngine;
import dev.orderhub.tx.PayoutWorker;
import dev.orderhub.tx.RecoveryService;
import dev.orderhub.tx.ReturnEngine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Einstiegspunkt von OrderHub. Nicht final: MockBukkit erzeugt per ByteBuddy eine Unterklasse. */
public class OrderHubPlugin extends JavaPlugin {

  private static final List<String> RESOURCES =
      List.of(
          "config.yml", "database.yml", "discord.yml", "menus.yml", "lang/en.yml", "lang/de.yml");

  private volatile Settings settings;
  private final Messages messages = new Messages();
  private PluginScheduler scheduler;
  private MenuManager menus;
  private JdbcStorage storage;
  private Services services;
  private ReturnQueue returnQueue;

  @Override
  public void onEnable() {
    this.scheduler = PluginScheduler.create(this);
    try {
      prepareFiles();
      loadSettings();
    } catch (IOException | RuntimeException e) {
      getLogger().log(Level.SEVERE, "configuration could not be loaded, disabling OrderHub", e);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    try {
      File dbFile = new File(getDataFolder(), "database.yml");
      storage =
          DatabaseSetup.open(
              YamlConfiguration.loadConfiguration(dbFile),
              getDataFolder().toPath(),
              settings.serverId());
    } catch (RuntimeException e) {
      getLogger()
          .log(Level.SEVERE, "database unavailable, disabling OrderHub: " + e.getMessage(), e);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    wire();
  }

  private void prepareFiles() throws IOException {
    java.nio.file.Files.createDirectories(new File(getDataFolder(), "lang").toPath());
    for (String name : RESOURCES) {
      File target = new File(getDataFolder(), name);
      try (InputStream in = getResource(name)) {
        if (in == null) {
          throw new IOException("bundled resource missing: " + name);
        }
        int added = ConfigUpdater.mergeDefaults(target.toPath(), in);
        if (added > 0) {
          getLogger().info(name + ": " + added + " new option(s) added with default values");
        }
      }
    }
  }

  private void loadSettings() {
    YamlConfiguration cfg =
        YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
    Settings loaded = Settings.from(cfg);
    loaded.warnings().forEach(w -> getLogger().warning("config.yml: " + w));
    messages.load(loaded.language(), getDataFolder().toPath(), getClassLoader());
    this.settings = loaded;
  }

  private void wire() {
    Settings st = settings;
    ItemCodec codec = new ItemCodec();
    List<String> blocked = new ArrayList<>(st.blockedMaterials());
    blocked.addAll(st.blockedTags());
    Blocklist.Parsed blocklist = Blocklist.parse(blocked, true);
    blocklist.warnings().forEach(w -> getLogger().warning("items.blocked: " + w));
    MatchPolicy policy = MatchPolicy.strict();
    List<RejectionReason> allowed = new ArrayList<>();
    if (st.allowCustomName()) {
      allowed.add(RejectionReason.CUSTOM_NAME);
    }
    if (st.allowLore()) {
      allowed.add(RejectionReason.LORE);
    }
    if (st.allowEnchanted()) {
      allowed.add(RejectionReason.ENCHANTED);
    }
    if (st.allowDamaged()) {
      allowed.add(RejectionReason.DAMAGED);
    }
    if (!allowed.isEmpty()) {
      policy = policy.allowing(allowed.toArray(new RejectionReason[0]));
    }
    ItemMatcher matcher = new ItemMatcher(codec, policy, blocklist.blocklist());

    EconomyBridge economy = new UnavailableEconomy();
    if (getServer().getPluginManager().isPluginEnabled("Vault")) {
      economy = VaultHook.create().orElse(economy);
      if (!VaultHook.providerPresent()) {
        getLogger().warning("Vault found but no economy provider is registered yet.");
      }
    } else {
      getLogger().warning("Vault is not installed: orders cannot be created or paid out.");
    }

    Limits limits =
        new Limits(
            st.minUnitPrice(),
            st.maxUnitPrice(),
            st.maxQuantity(),
            st.defaultMaxOpenOrders(),
            List.of(
                new Limits.Tier("orderhub.limit.5", 5),
                new Limits.Tier("orderhub.limit.10", 10),
                new Limits.Tier("orderhub.limit.25", 25),
                new Limits.Tier("orderhub.limit.50", 50)));
    Fees fees = new Fees(st.createFeeBp(), st.salesTaxBp(), true);
    Expiry expiry = new Expiry(Duration.ofHours(st.expiryHours()), true);
    AuditSink audit =
        new ConsoleAudit(
            getLogger(),
            message ->
                scheduler.runGlobal(() -> {
                  for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("orderhub.admin")) {
                      scheduler.tryRunOn(
                          p,
                          () ->
                              p.sendMessage(
                                  messages.prefixed(
                                      "admin.warning", Messages.text("message", message))));
                    }
                  }
                }));

    OrderService orders = new OrderService(storage, economy, limits, fees, expiry);
    DeliveryEngine delivery = new DeliveryEngine(storage, fees);
    ClaimEngine claims = new ClaimEngine(storage, audit);
    ReturnEngine returns = new ReturnEngine(storage, audit);
    PayoutWorker payouts =
        new PayoutWorker(
            storage,
            economy,
            Clock.systemUTC(),
            st.payoutMaxAttempts(),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5));
    // Laufender Betrieb (periodisch und per Admin): fremde oder gerade laufende Vorgaenge bleiben
    // zwei Minuten unangetastet, sonst wuerde die Recovery eine laufende Lieferung zurueckrollen.
    RecoveryService recovery =
        new RecoveryService(
            storage, claims, returns, audit, Clock.systemUTC(), Duration.ofMinutes(2));
    // Nur beim Start eines einzelnen Servers: dort kann nichts mehr laufen, alle offenen Eintraege
    // stammen aus dem vorigen Lauf. Bei mehreren Servern bleibt die Wartezeit bestehen.
    RecoveryService startupRecovery =
        storage.dialect() == dev.orderhub.storage.Dialect.SQLITE
            ? new RecoveryService(storage, claims, returns, audit, Clock.systemUTC(), Duration.ZERO)
            : recovery;

    returnQueue = new ReturnQueue(storage, scheduler, codec, getLogger());
    menus =
        new MenuManager(
            getLogger(),
            scheduler,
            (playerId, items) -> returnQueue.persist(playerId, items, !isEnabled()),
            new MenuConfig(st.clickDebounceMs()));

    DiscordService discord = new DiscordService(storage, this::discordConfig, getLogger());
    InsightsService insights =
        new InsightsService(
            storage,
            discord,
            () -> settings.notificationCooldownSeconds(),
            () -> settings.minTradesForRecommendation());
    services =
        new Services(
            getLogger(),
            storage,
            scheduler,
            messages,
            () -> settings,
            limits,
            orders,
            delivery,
            claims,
            returns,
            payouts,
            codec,
            matcher,
            menus,
            insights,
            discord);
    InsightsFlow insightsFlow = new InsightsFlow(services);
    ChatInput chat =
        new ChatInput(scheduler, () -> settings.cancelWord(), System::currentTimeMillis);
    CreateFlow create = new CreateFlow(services, chat);
    List<Integer> inputSlots = deliverySlots();
    DeliveryFlow deliveryFlow = new DeliveryFlow(services, inputSlots);
    ParcelFlow claimFlow = new ParcelFlow(services, ParcelFlow.Kind.CLAIM);
    ParcelFlow returnFlow = new ParcelFlow(services, ParcelFlow.Kind.RETURN);
    Menus menuViews = new Menus(services, deliveryFlow, claimFlow);

    AdminService admin = new AdminService(storage, orders, recovery);
    register(
        "order",
        new OrderCommand(
            services, create, deliveryFlow, menuViews, claimFlow, returnFlow, insightsFlow));
    OrderAdminCommand adminCommand = new OrderAdminCommand(services, admin, this::reloadConfig0);
    register("orderadmin", adminCommand);

    getServer()
        .getServicesManager()
        .register(
            dev.orderhub.api.OrderHubApi.class,
            new dev.orderhub.integration.ApiImpl(services),
            this,
            org.bukkit.plugin.ServicePriority.Normal);
    var pm = getServer().getPluginManager();
    pm.registerEvents(new MenuListener(menus, getLogger()), this);
    pm.registerEvents(chat, this);
    pm.registerEvents(new JoinListener(services, returnFlow, claimFlow), this);

    // Recovery zuerst, die periodischen Worker starten erst danach (auch wenn Recovery
    // fehlschlaegt).
    Runnable startWorkers =
        () -> {
          scheduler.runAsyncRepeating(
              () -> guarded("payout worker", () -> payouts.runOnce(25)),
              5_000,
              st.payoutWorkerIntervalSeconds() * 1000L);
          scheduler.runAsyncRepeating(
              () ->
                  guarded(
                      "expiry sweep",
                      () ->
                          orders.expireDue(
                              100,
                              id ->
                                  dev.orderhub.integration.Events.fire(
                                      new dev.orderhub.api.event.OrderExpireEvent(id)))),
              15_000,
              60_000);
          scheduler.runAsyncRepeating(
              () -> guarded("recovery sweep", recovery::sweep), 300_000, 300_000);
          DiscordWorker discordWorker =
              new DiscordWorker(storage, new WebhookSender(), discordWorkerConfig(), getLogger());
          scheduler.runAsyncRepeating(
              () ->
                  guarded(
                      "discord worker", () -> discordWorker.runOnce(System.currentTimeMillis())),
              10_000,
              5_000);
          scheduler.runAsyncRepeating(
              () ->
                  guarded(
                      "history cleanup",
                      () ->
                          insights.purge(
                              settings.historyRetentionDays(), System.currentTimeMillis())),
              120_000,
              settings.cleanupIntervalMinutes() * 60_000L);
        };
    scheduler.runAsync(() -> {
      try {
        startupRecovery.runAtStartup();
      } catch (RuntimeException e) {
        getLogger().log(Level.SEVERE, "startup recovery failed", e);
      } finally {
        startWorkers.run();
      }
    });
    if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      new dev.orderhub.placeholder.OrderHubExpansion(
              services,
              getPluginMeta().getVersion(),
              new dev.orderhub.placeholder.PlaceholderCache(
                  st.cacheMaxEntries(),
                  15_000,
                  System::currentTimeMillis,
                  task -> scheduler.runAsync(task)))
          .register();
    }
    getLogger()
        .info("OrderHub enabled (server-id " + st.serverId() + ", " + storage.dialect() + ")");
  }

  private YamlConfiguration discordYml() {
    return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "discord.yml"));
  }

  private java.util.Optional<java.net.URI> webhookUri() {
    YamlConfiguration yml = discordYml();
    String env = System.getenv("ORDERHUB_DISCORD_WEBHOOK");
    String raw = env != null && !env.isBlank() ? env : yml.getString("webhook-url", "");
    var uri = WebhookUrl.validate(raw);
    if (uri.isEmpty() && raw != null && !raw.isBlank()) {
      getLogger().warning("discord.yml: webhook-url is not a valid Discord https URL, ignoring it");
    }
    return uri;
  }

  private DiscordService.Config discordConfig() {
    YamlConfiguration yml = discordYml();
    if (!yml.getBoolean("enabled", false)) {
      return DiscordService.Config.disabled();
    }
    java.util.Set<DiscordService.Event> events = new java.util.HashSet<>();
    for (DiscordService.Event e : DiscordService.Event.values()) {
      if (yml.getBoolean("events." + e.key(), false)) {
        events.add(e);
      }
    }
    long threshold;
    try {
      threshold =
          dev.orderhub.money.Money.parse(
              String.valueOf(yml.get("large-order-threshold", "100000")));
    } catch (NumberFormatException ex) {
      threshold = dev.orderhub.money.Money.parse("100000");
    }
    return new DiscordService.Config(
        true,
        java.util.Objects.requireNonNullElse(yml.getString("username"), "OrderHub"),
        events,
        threshold);
  }

  private DiscordWorker.Config discordWorkerConfig() {
    return new DiscordWorker.Config(
        () -> discordYml().getBoolean("enabled", false) ? webhookUri() : java.util.Optional.empty(),
        Math.max(1, discordYml().getInt("delivery.max-attempts", 6)),
        Math.max(1, discordYml().getLong("delivery.initial-backoff-seconds", 5)),
        Math.max(5, discordYml().getLong("delivery.max-backoff-seconds", 300)));
  }

  private List<Integer> deliverySlots() {
    YamlConfiguration menusYml =
        YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menus.yml"));
    List<Integer> slots = new ArrayList<>(menusYml.getIntegerList("delivery.input-slots"));
    slots.removeIf(slot -> slot < 0 || slot > 44);
    if (slots.isEmpty()) {
      for (int i = 10; i <= 16; i++) {
        slots.add(i);
      }
    }
    return slots;
  }

  private void guarded(String name, Runnable task) {
    try {
      task.run();
    } catch (RuntimeException e) {
      getLogger().log(Level.WARNING, name + " failed", e);
    }
  }

  private void register(String name, org.bukkit.command.TabExecutor executor) {
    PluginCommand command =
        Objects.requireNonNull(getCommand(name), name + " missing in plugin.yml");
    command.setExecutor(executor);
    command.setTabCompleter(executor);
  }

  /**
   * Laedt Texte und weiche Einstellungen neu. Preise, Gebuehren und Datenbank brauchen einen
   * Neustart.
   */
  private void reloadConfig0() {
    loadSettings();
    getLogger().info("configuration reloaded (database, fees and limits need a restart)");
  }

  @Override
  public void onDisable() {
    if (menus != null) {
      menus.closeAll();
    }
    if (scheduler != null) {
      scheduler.cancelAll();
    }
    if (storage != null) {
      storage.close();
    }
  }

  public PluginScheduler scheduler() {
    return scheduler;
  }

  public MenuManager menus() {
    return menus;
  }

  public Settings settings() {
    return settings;
  }

  public Path dataPath() {
    return getDataFolder().toPath();
  }
}
