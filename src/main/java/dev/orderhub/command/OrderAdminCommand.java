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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /orderadmin. Schreibende Befehle verlangen {@code confirm <Begruendung>} und werden im Audit-Log
 * vermerkt. Datenbankarbeit laeuft asynchron.
 */
public final class OrderAdminCommand implements org.bukkit.command.TabExecutor {

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
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      @NotNull String[] args) {
    if (!sender.hasPermission("orderhub.admin")) {
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
      case "list" -> async(sender, () -> list(sender, args));
      case "cancel" -> {
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
      case "statistics" ->
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
      case "debug" ->
          sender.sendMessage(
              Component.text(
                  "server-id="
                      + s.storage().serverId()
                      + " dialect="
                      + s.storage().dialect()
                      + " menus-open="
                      + s.menus().openCount()));
      default -> sender.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
    return true;
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
      reply(sender, Component.text(entry.map(JournalEntry::toString).orElse("not found")));
      for (var l : admin.ledgerOf(id)) {
        reply(
            sender,
            Component.text("  " + l.kind() + " " + Money.format(l.amount()) + " " + l.player()));
      }
    } catch (IllegalArgumentException e) {
      reply(
          sender, s.messages().prefixed("general.invalid-number", Messages.text("input", args[1])));
    }
  }

  @Override
  public List<String> onTabComplete(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String alias,
      @NotNull String[] args) {
    if (!sender.hasPermission("orderhub.admin")) {
      return List.of();
    }
    if (args.length == 1) {
      return OrderCommand.filter(SUBCOMMANDS, args[0]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
      return OrderCommand.filter(
          Arrays.stream(OrderStatus.values()).map(o -> o.name().toLowerCase(Locale.ROOT)).toList(),
          args[1]);
    }
    return List.of();
  }
}
