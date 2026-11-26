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
          "debug");

  private final Services s;
  private final AdminService admin;
  private final Runnable reload;

  public OrderAdminCommand(Services services, AdminService admin, Runnable reload) {
    this.s = services;
    this.admin = admin;
    this.reload = reload;
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
              reply(sender, Component.text(report.summary()));
            });
      }
      case "statistics" -> {
        if (need(sender, "orderhub.admin.view")) {
          async(
              sender,
              () -> {
                var st = admin.statistics();
                reply(
                    sender,
                    Component.text(
                        "active="
                            + st.active()
                            + " paused="
                            + st.paused()
                            + " completed="
                            + st.completed()
                            + " cancelled="
                            + st.cancelled()
                            + " expired="
                            + st.expired()
                            + " manual-review="
                            + st.manualReview()));
              });
        }
      }
      case "debug" -> {
        if (need(sender, "orderhub.admin.view")) {
          sender.sendMessage(
              Component.text(
                  "server-id="
                      + s.storage().serverId()
                      + " dialect="
                      + s.storage().dialect()
                      + " menus-open="
                      + s.menus().openCount()));
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
                reply(sender, Component.text("MANUAL_REVIEW: " + open.size()));
                for (JournalEntry e : open) {
                  reply(
                      sender,
                      Component.text(
                          e.id()
                              + " "
                              + e.type()
                              + " "
                              + Money.format(e.amount())
                              + " player="
                              + e.playerId()
                              + " "
                              + String.valueOf(e.detail())));
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
          sender.sendMessage(
              Component.text(
                  "/orderadmin review resolve <tx> paid|refund|discard confirm <reason>"));
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
                  Component.text(
                      "review "
                          + tx
                          + " "
                          + how
                          + ": "
                          + r.status()
                          + (r.refundTx() == null ? "" : " refund-tx=" + r.refundTx())));
            });
      }
      default -> sender.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
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
            "orderhub.admin.recovery")) {
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
          Component.text(
              "#"
                  + o.id()
                  + " "
                  + o.item().itemKey()
                  + " "
                  + o.quantityFilled()
                  + "/"
                  + o.quantityTotal()
                  + " @"
                  + Money.format(o.unitPrice())
                  + " owner="
                  + o.owner()
                  + " "
                  + o.status()));
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
      reply(sender, Component.text(order.map(Order::toString).orElse("not found")));
      return;
    }
    try {
      UUID id = UUID.fromString(args[1]);
      var entry = admin.transaction(id);
      reply(
          sender,
          entry
              .map(e -> s.messages().get("admin.transaction", Messages.text("entry", e.toString())))
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
    if (args.length == 2 && args[0].equalsIgnoreCase("review")) {
      return OrderCommand.filter(List.of("list", "show", "resolve"), args[1]);
    }
    if (args.length == 4
        && args[0].equalsIgnoreCase("review")
        && args[1].equalsIgnoreCase("resolve")) {
      return OrderCommand.filter(List.of("paid", "refund", "discard"), args[3]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
      return OrderCommand.filter(
          Arrays.stream(OrderStatus.values()).map(o -> o.name().toLowerCase(Locale.ROOT)).toList(),
          args[1]);
    }
    return List.of();
  }
}
