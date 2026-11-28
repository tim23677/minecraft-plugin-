package dev.orderhub.command;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.flow.Services;
import dev.orderhub.money.Money;
import dev.orderhub.service.AdminService;
import dev.orderhub.tx.JournalEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /orderadmin. Schreibende Befehle verlangen {@code confirm <Begruendung>} und werden im Audit-Log
 * vermerkt. Datenbankarbeit laeuft asynchron.
 */
public final class OrderAdminCommand implements CommandHandler {

  private static final List<String> SUBCOMMANDS =
      List.of(
          "reload",
          "list",
          "cancel",
          "purge-expired",
          "transactions",
          "transaction",
          "recovery",
          "statistics",
          "inspect",
          "review",
          "health",
          "backup",
          "export",
          "dump",
          "player",
          "audit",
          "debug");

  private final Services s;
  private final AdminService admin;
  private final Runnable reload;
  private final java.nio.file.Path dataDir;

  public OrderAdminCommand(
      Services services, AdminService admin, Runnable reload, java.nio.file.Path dataDir) {
    this.s = services;
    this.admin = admin;
    this.reload = reload;
    this.dataDir = dataDir;
  }

  @Override
  public boolean canUse(@NotNull CommandSender sender) {
    return hasAnyAdmin(sender);
  }

  @Override
  public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
    if (!hasAnyAdmin(sender)) {
      sender.sendMessage(s.messages().prefixed("general.no-permission"));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(s.messages().get("admin.header"));
      sender.sendMessage(s.messages().get("admin.help"));
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    String actor = sender instanceof Player p ? p.getName() : "console";
    switch (sub) {
      case "reload" -> {
        if (!need(sender, "orderhub.admin.reload")) {
          return true;
        }
        reload.run();
        sender.sendMessage(s.messages().prefixed("general.reloaded"));
        async(sender, () -> admin.auditReload(actor, "reloaded by " + actor));
      }
      case "list" -> {
        if (need(sender, "orderhub.admin.view")) {
          async(sender, () -> list(sender, args));
        }
      }
      case "cancel" -> {
        if (!need(sender, "orderhub.admin.cancel")) {
          return true;
        }
        if (args.length < 2 || !args[1].matches("[0-9]{1,18}")) {
          sender.sendMessage(
              s.messages()
                  .prefixed(
                      "general.invalid-number",
                      Messages.text("input", args.length < 2 ? "" : args[1])));
          return true;
        }
        String reason = confirmedReason(sender, args, 2);
        if (reason == null) {
          return true;
        }
        long id = Long.parseLong(args[1]);
        async(
            sender,
            () -> {
              var r = admin.cancel(id, actor, reason);
              if (r.ok()) {
                dev.orderhub.integration.Events.fire(
                    new dev.orderhub.api.event.OrderCancelEvent(
                        id, dev.orderhub.service.AdminService.SYSTEM, r.refunded()));
              }
              reply(
                  sender,
                  r.ok()
                      ? s.messages().prefixed("admin.cancelled", Messages.number("id", id))
                      : s.messages().prefixed("order.not-found", Messages.number("id", id)));
            });
      }
      case "purge-expired" -> {
        if (!need(sender, "orderhub.admin.recovery")) {
          return true;
        }
        String reason = confirmedReason(sender, args, 1);
        if (reason == null) {
          return true;
        }
        async(
            sender,
            () -> {
              var r = admin.purgeExpired(actor, reason);
              reply(
                  sender,
                  s.messages()
                      .prefixed(
                          "admin.purge-done", Messages.number("count", r.expired() + r.renewed())));
            });
      }
      case "transactions" -> {
        if (!need(sender, "orderhub.admin.transactions")) {
          return true;
        }
        async(sender, () -> transactions(sender, args));
      }
      case "transaction", "inspect" -> {
        if (!need(sender, "orderhub.admin.transactions")) {
          return true;
        }
        async(sender, () -> inspect(sender, sub, args));
      }
      case "review" -> review(sender, args, actor);
      case "health" -> {
        if (need(sender, "orderhub.admin.view")) {
          async(sender, () -> health(sender));
        }
      }
      case "dump" -> {
        if (need(sender, "orderhub.admin.dump")) {
          async(sender, () -> dump(sender));
        }
      }
      case "backup" -> {
        if (!need(sender, "orderhub.admin.backup")) {
          return true;
        }
        String reason = confirmedReason(sender, args, 1);
        if (reason == null) {
          return true;
        }
        async(
            sender,
            () -> {
              try {
                var r = admin.backup(dataDir, actor, reason);
                reply(
                    sender,
                    s.messages()
                        .prefixed("admin.backup-done", Messages.text("file", r.file().toString())));
              } catch (IllegalStateException e) {
                reply(
                    sender,
                    s.messages()
                        .prefixed(
                            "admin.backup-failed",
                            Messages.text("reason", String.valueOf(e.getMessage()))));
              }
            });
      }
      case "export" -> {
        if (!need(sender, "orderhub.admin.backup")) {
          return true;
        }
        if (args.length < 2 || !(args[1].equals("orders") || args[1].equals("ledger"))) {
          sender.sendMessage(s.messages().prefixed("admin.export-usage"));
          return true;
        }
        String reason = confirmedReason(sender, args, 2);
        if (reason == null) {
          return true;
        }
        String what = args[1];
        async(
            sender,
            () -> {
              try {
                var r = admin.export(what, dataDir, actor, reason);
                reply(
                    sender,
                    s.messages()
                        .prefixed(
                            "admin.export-done",
                            Messages.text("file", r.file().toString()),
                            Messages.number("rows", r.rows())));
              } catch (IllegalStateException e) {
                reply(
                    sender,
                    s.messages()
                        .prefixed(
                            "admin.backup-failed",
                            Messages.text("reason", String.valueOf(e.getMessage()))));
              }
            });
      }
      case "recovery" -> {
        if (!need(sender, "orderhub.admin.recovery")) {
          return true;
        }
        String reason = confirmedReason(sender, args, 1);
        if (reason == null) {
          return true;
        }
        sender.sendMessage(s.messages().prefixed("admin.recovery-started"));
        async(
            sender,
            () -> {
              var report = admin.runRecovery(actor, reason);
              reply(
                  sender,
                  s.messages()
                      .get("admin.recovery-summary", Messages.text("summary", report.summary())));
            });
      }
      case "player" -> {
        if (need(sender, "orderhub.admin.view")) {
          player(sender, args);
        }
      }
      case "audit" -> {
        if (need(sender, "orderhub.admin.transactions")) {
          audit(sender, args);
        }
      }
      case "statistics" -> {
        if (need(sender, "orderhub.admin.view")) {
          async(
              sender,
              () -> {
                var st = admin.statistics();
                reply(
                    sender,
                    s.messages()
                        .get(
                            "admin.statistics",
                            Messages.number("active", st.active()),
                            Messages.number("paused", st.paused()),
                            Messages.number("completed", st.completed()),
                            Messages.number("cancelled", st.cancelled()),
                            Messages.number("expired", st.expired()),
                            Messages.number("review", st.manualReview())));
              });
        }
      }
      case "debug" -> {
        if (need(sender, "orderhub.admin.view")) {
          sender.sendMessage(
              s.messages()
                  .get(
                      "admin.debug",
                      Messages.text("server", s.storage().serverId()),
                      Messages.text("dialect", String.valueOf(s.storage().dialect())),
                      Messages.number("menus", s.menus().openCount())));
        }
      }
      default -> sender.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
    return true;
  }

  private void review(CommandSender sender, String[] args, String actor) {
    String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
    switch (action) {
      case "list" -> {
        if (need(sender, "orderhub.admin.transactions")) {
          async(
              sender,
              () -> {
                var open = admin.manualReview(50);
                reply(
                    sender,
                    s.messages().get("admin.review-header", Messages.number("count", open.size())));
                for (JournalEntry e : open) {
                  reply(
                      sender,
                      s.messages()
                          .get(
                              "admin.review-line",
                              Messages.text("id", e.id().toString()),
                              Messages.text("type", e.type().name()),
                              Messages.text("amount", Money.format(e.amount())),
                              Messages.text("player", e.playerId().toString()),
                              Messages.text("detail", String.valueOf(e.detail()))));
                }
              });
        }
      }
      case "show" -> {
        if (need(sender, "orderhub.admin.transactions")) {
          if (args.length < 3) {
            sender.sendMessage(
                s.messages().prefixed("general.invalid-number", Messages.text("input", "")));
            return;
          }
          async(
              sender, () -> inspect(sender, "transaction", new String[] {"transaction", args[2]}));
        }
      }
      case "resolve" -> {
        if (!need(sender, "orderhub.admin.recovery")) {
          return;
        }
        if (args.length < 4) {
          sender.sendMessage(s.messages().prefixed("admin.review-usage"));
          return;
        }
        UUID tx;
        AdminService.Resolution how;
        try {
          tx = UUID.fromString(args[2]);
          how = AdminService.Resolution.valueOf(args[3].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          sender.sendMessage(
              s.messages().prefixed("general.invalid-number", Messages.text("input", args[2])));
          return;
        }
        String reason = confirmedReason(sender, args, 4);
        if (reason == null) {
          return;
        }
        async(
            sender,
            () -> {
              var r = admin.resolveReview(tx, how, actor, reason);
              reply(
                  sender,
                  s.messages()
                      .get(
                          "admin.review-result",
                          Messages.text("tx", tx.toString()),
                          Messages.text("how", how.name()),
                          Messages.text("status", r.status().name()),
                          Messages.text(
                              "refund", r.refundTx() == null ? "-" : r.refundTx().toString())));
            });
      }
      default -> sender.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
  }

  /** Schreibt die Support-Datei nach {@code <dataDir>/dumps} (Async-Thread). */
  private void dump(CommandSender sender) {
    var plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("OrderHub");
    java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
    facts.put("orderhub", plugin == null ? "?" : plugin.getPluginMeta().getVersion());
    facts.put("server", org.bukkit.Bukkit.getVersion());
    facts.put("java", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
    facts.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    facts.put(
        "folia",
        String.valueOf(
            org.bukkit.Bukkit.getVersion().toLowerCase(java.util.Locale.ROOT).contains("folia")));
    facts.put("database", String.valueOf(s.storage().dialect()));
    facts.put("server-id", s.storage().serverId());
    var health = admin.health();
    facts.put(
        "payments",
        health.openPayments()
            + " open, "
            + health.paymentsInProgress()
            + " in progress, "
            + health.manualReview()
            + " in manual review");
    facts.put(
        "economy latency ms",
        "median " + health.economy().medianMillis() + ", max " + health.economy().maxMillis());
    List<String> plugins = new java.util.ArrayList<>();
    for (var p : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
      plugins.add(
          p.getName()
              + " "
              + p.getPluginMeta().getVersion()
              + (p.isEnabled() ? "" : " (disabled)"));
    }
    java.util.Map<String, Object> flat = new java.util.LinkedHashMap<>();
    var cfg =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            dataDir.resolve("config.yml").toFile());
    for (String key : cfg.getKeys(true)) {
      if (!cfg.isConfigurationSection(key)) {
        flat.put(key, cfg.get(key));
      }
    }
    List<String> log = List.of();
    try {
      java.nio.file.Path pluginsDir = dataDir.toAbsolutePath().getParent();
      java.nio.file.Path serverDir = pluginsDir == null ? null : pluginsDir.getParent();
      java.nio.file.Path latest =
          serverDir == null
              ? dataDir.resolve("latest.log")
              : serverDir.resolve("logs").resolve("latest.log");
      if (java.nio.file.Files.isRegularFile(latest)) {
        log =
            dev.orderhub.bootstrap.SupportDump.tailLines(
                latest, dev.orderhub.bootstrap.SupportDump.LOG_TAIL_BYTES);
      }
    } catch (java.io.IOException | RuntimeException e) {
      // Ohne lesbares Log bleibt der Abschnitt leer.
    }
    String text =
        dev.orderhub.bootstrap.SupportDump.compose(
            facts,
            plugins,
            dev.orderhub.bootstrap.SupportDump.configLines(flat),
            dev.orderhub.bootstrap.SupportDump.relevantLogLines(log, 200));
    try {
      java.nio.file.Path dir = dataDir.resolve("dumps");
      java.nio.file.Files.createDirectories(dir);
      java.nio.file.Path file =
          dir.resolve(
              "dump-"
                  + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                      .withZone(java.time.ZoneOffset.UTC)
                      .format(java.time.Instant.now())
                  + ".txt");
      java.nio.file.Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
      reply(
          sender, s.messages().prefixed("admin.dump-done", Messages.text("file", file.toString())));
    } catch (java.io.IOException e) {
      reply(
          sender,
          s.messages()
              .prefixed(
                  "admin.backup-failed", Messages.text("reason", String.valueOf(e.getMessage()))));
    }
  }

  private void health(CommandSender sender) {
    var h = admin.health();
    var st = h.storage();
    reply(sender, s.messages().get("admin.header"));
    reply(
        sender,
        s.messages()
            .get(
                "admin.health-system",
                Messages.text("version", pluginVersion()),
                Messages.text(
                    "platform", dev.orderhub.scheduler.FoliaDetector.isFolia() ? "Folia" : "Paper"),
                Messages.text("java", System.getProperty("java.version", "?")),
                Messages.text("server", s.settings().get().serverId()),
                Messages.text("uptime", uptime())));
    reply(
        sender,
        s.messages()
            .get(
                "admin.health-db",
                Messages.number("active", st.poolActive()),
                Messages.number("idle", st.poolIdle()),
                Messages.number("waiting", st.poolWaiting()),
                Messages.number("total", st.poolTotal()),
                Messages.number("queued", st.queued()),
                Messages.number("running", st.running())));
    reply(
        sender,
        s.messages()
            .get(
                "admin.health-payments",
                Messages.number("open", h.openPayments()),
                Messages.number("progress", h.paymentsInProgress()),
                Messages.number("review", h.manualReview())));
    reply(
        sender,
        s.messages()
            .get(
                "admin.health-economy",
                Messages.number("samples", h.economy().samples()),
                Messages.number("median", h.economy().medianMillis()),
                Messages.number("max", h.economy().maxMillis()),
                Messages.number("last", h.economy().lastMillis()),
                Messages.number("oldest", h.oldestOpenPaymentAgeMillis() / 1000)));
    reply(
        sender,
        h.lastRecovery() == null
            ? s.messages().get("admin.health-recovery-none")
            : s.messages()
                .get(
                    "admin.health-recovery",
                    Messages.text(
                        "at", java.time.Instant.ofEpochMilli(h.lastRecoveryAt()).toString()),
                    Messages.text("summary", h.lastRecovery())));
  }

  private static String pluginVersion() {
    try {
      return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(OrderAdminCommand.class)
          .getPluginMeta()
          .getVersion();
    } catch (RuntimeException e) {
      return "?";
    }
  }

  private static String uptime() {
    long minutes = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 60_000;
    return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
  }

  private static final java.util.regex.Pattern UUID_TEXT =
      java.util.regex.Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /** {@code /orderadmin player <name|uuid>}: Zahlen eines Spielers fuer die Diagnose. */
  private void player(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage(s.messages().prefixed("admin.player-usage"));
      return;
    }
    java.util.UUID id;
    String label = args[1];
    if (UUID_TEXT.matcher(args[1]).matches()) {
      id = java.util.UUID.fromString(args[1]);
    } else {
      org.bukkit.OfflinePlayer known = org.bukkit.Bukkit.getOfflinePlayerIfCached(args[1]);
      if (known == null) {
        sender.sendMessage(
            s.messages().prefixed("admin.player-not-found", Messages.text("input", args[1])));
        return;
      }
      id = known.getUniqueId();
      label = known.getName() == null ? args[1] : known.getName();
    }
    String shown = label;
    async(
        sender,
        () -> {
          var o = admin.playerOverview(id);
          reply(
              sender,
              s.messages()
                  .prefixed(
                      "admin.player-overview",
                      Messages.text("player", shown),
                      Messages.number("open", o.openOrders()),
                      Messages.number("claims", o.claimsWaiting()),
                      Messages.number("returns", o.returnsWaiting()),
                      Messages.number("completed", o.ordersCompleted()),
                      Messages.number("delivered", o.itemsDelivered()),
                      Messages.text("earned", Money.format(o.moneyEarned())),
                      Messages.text("spent", Money.format(o.moneySpent()))));
        });
  }

  /** {@code /orderadmin audit [n]}: die letzten Eintraege des Audit-Protokolls. */
  private void audit(CommandSender sender, String[] args) {
    int limit = 10;
    if (args.length > 1) {
      if (!args[1].matches("[0-9]{1,3}")) {
        sender.sendMessage(
            s.messages().prefixed("general.invalid-number", Messages.text("input", args[1])));
        return;
      }
      limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
    }
    int shown = limit;
    async(
        sender,
        () -> {
          var entries = admin.recentAudit(shown);
          if (entries.isEmpty()) {
            reply(sender, s.messages().prefixed("admin.audit-empty"));
            return;
          }
          for (var e : entries) {
            reply(
                sender,
                s.messages()
                    .get(
                        "admin.audit-line",
                        Messages.text(
                            "time", java.time.Instant.ofEpochMilli(e.createdAt()).toString()),
                        Messages.text("actor", e.actor()),
                        Messages.text("action", e.action()),
                        Messages.text("target", e.target() == null ? "-" : e.target()),
                        Messages.text("reason", e.reason() == null ? "" : e.reason())));
          }
        });
  }

  private String confirmedReason(CommandSender sender, String[] args, int from) {
    if (args.length <= from || !args[from].equalsIgnoreCase("confirm")) {
      sender.sendMessage(s.messages().prefixed("admin.confirm-required"));
      return null;
    }
    String reason = String.join(" ", Arrays.copyOfRange(args, from + 1, args.length)).trim();
    if (reason.isEmpty()) {
      sender.sendMessage(s.messages().prefixed("admin.reason-required"));
      return null;
    }
    return reason;
  }

  private static boolean hasAnyAdmin(CommandSender sender) {
    for (String permission :
        List.of(
            "orderhub.admin",
            "orderhub.admin.view",
            "orderhub.admin.cancel",
            "orderhub.admin.reload",
            "orderhub.admin.transactions",
            "orderhub.admin.recovery",
            "orderhub.admin.backup",
            "orderhub.admin.dump")) {
      if (sender.hasPermission(permission)) {
        return true;
      }
    }
    return false;
  }

  private boolean need(CommandSender sender, String permission) {
    if (sender.hasPermission(permission)) {
      return true;
    }
    sender.sendMessage(s.messages().prefixed("general.no-permission"));
    return false;
  }

  private void async(CommandSender sender, Runnable work) {
    s.scheduler()
        .runAsync(() -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            s.logger().log(java.util.logging.Level.WARNING, "admin command failed", e);
            reply(sender, s.messages().prefixed("general.database-error"));
          }
        });
  }

  /** Antwort von einem Async-Thread: Spieler im Entity-Kontext, Konsole direkt. */
  private void reply(CommandSender sender, Component message) {
    if (sender instanceof Player player) {
      s.scheduler().tryRunOn(player, () -> player.sendMessage(message));
    } else {
      sender.sendMessage(message);
    }
  }

  private void list(CommandSender sender, String[] args) {
    OrderStatus status = OrderStatus.ACTIVE;
    if (args.length > 1) {
      try {
        status = OrderStatus.valueOf(args[1].toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // Standard bleibt ACTIVE
      }
    }
    List<Order> orders =
        admin.list(new OrderQuery(null, null, null, status, OrderQuery.Sort.NEWEST, 0, 20));
    reply(sender, s.messages().get("admin.header"));
    for (Order o : orders) {
      reply(
          sender,
          s.messages()
              .get(
                  "admin.order-line",
                  Messages.number("id", o.id()),
                  Messages.text("item", o.item().itemKey()),
                  Messages.number("filled", o.quantityFilled()),
                  Messages.number("total", o.quantityTotal()),
                  Messages.text("unit", Money.format(o.unitPrice())),
                  Messages.text("owner", o.owner().toString()),
                  Messages.text("status", o.status().name())));
    }
  }

  private void transactions(CommandSender sender, String[] args) {
    List<JournalEntry> entries;
    if (args.length > 1) {
      try {
        entries = admin.transactionsOfPlayer(UUID.fromString(args[1]), 0, 20);
      } catch (IllegalArgumentException e) {
        reply(
            sender,
            s.messages().prefixed("general.invalid-number", Messages.text("input", args[1])));
        return;
      }
    } else {
      entries = admin.recentTransactions(0, 20);
    }
    for (JournalEntry e : entries) {
      reply(
          sender,
          s.messages()
              .get(
                  "admin.transaction",
                  Messages.text("id", e.id().toString()),
                  Messages.text("state", e.state().name()),
                  Messages.text("amount", Money.format(e.amount())),
                  Messages.text("player", e.playerId().toString())));
    }
  }

  private void inspect(CommandSender sender, String sub, String[] args) {
    if (args.length < 2) {
      reply(sender, s.messages().prefixed("general.invalid-number", Messages.text("input", "")));
      return;
    }
    if (sub.equals("inspect") && args[1].matches("[0-9]{1,18}")) {
      var order = admin.order(Long.parseLong(args[1]));
      reply(
          sender,
          order
              .map(o -> s.messages().get("admin.entry", Messages.text("entry", o.toString())))
              .orElseGet(
                  () -> s.messages().prefixed("order.not-found", Messages.text("id", args[1]))));
      return;
    }
    try {
      UUID id = UUID.fromString(args[1]);
      var entry = admin.transaction(id);
      reply(
          sender,
          entry
              .map(e -> s.messages().get("admin.entry", Messages.text("entry", e.toString())))
              .orElseGet(() -> s.messages().prefixed("admin.transaction-not-found")));
      for (var l : admin.ledgerOf(id)) {
        reply(
            sender,
            s.messages()
                .get(
                    "admin.ledger-line",
                    Messages.text("kind", String.valueOf(l.kind())),
                    Messages.text("amount", Money.format(l.amount())),
                    Messages.text("player", String.valueOf(l.player()))));
      }
    } catch (IllegalArgumentException e) {
      reply(
          sender, s.messages().prefixed("general.invalid-number", Messages.text("input", args[1])));
    }
  }

  @Override
  public List<String> complete(@NotNull CommandSender sender, @NotNull String[] args) {
    if (!hasAnyAdmin(sender)) {
      return List.of();
    }
    if (args.length == 1) {
      return OrderCommand.filter(SUBCOMMANDS, args[0]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("export")) {
      return OrderCommand.filter(List.of("orders", "ledger"), args[1]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("review")) {
      return OrderCommand.filter(List.of("list", "show", "resolve"), args[1]);
    }
    if (args.length == 4
        && args[0].equalsIgnoreCase("review")
        && args[1].equalsIgnoreCase("resolve")) {
      return OrderCommand.filter(List.of("paid", "refund", "discard"), args[3]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
      return OrderCommand.filter(
          org.bukkit.Bukkit.getOnlinePlayers().stream()
              .map(org.bukkit.entity.Player::getName)
              .toList(),
          args[1]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("audit")) {
      return OrderCommand.filter(List.of("10", "25", "50"), args[1]);
    }
    if (args.length == 3 && args[0].equalsIgnoreCase("cancel")) {
      return OrderCommand.filter(List.of("confirm"), args[2]);
    }
    if (args.length == 2
        && List.of("purge-expired", "backup", "recovery")
            .contains(args[0].toLowerCase(Locale.ROOT))) {
      return OrderCommand.filter(List.of("confirm"), args[1]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
      return OrderCommand.filter(
          Arrays.stream(OrderStatus.values()).map(o -> o.name().toLowerCase(Locale.ROOT)).toList(),
          args[1]);
    }
    return List.of();
  }
}
