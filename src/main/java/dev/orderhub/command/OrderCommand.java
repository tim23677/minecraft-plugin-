package dev.orderhub.command;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.flow.CreateFlow;
import dev.orderhub.flow.DeliveryFlow;
import dev.orderhub.flow.Menus;
import dev.orderhub.flow.ParcelFlow;
import dev.orderhub.flow.Services;
import dev.orderhub.gui.MainMenu;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /order mit Unterbefehlen. Jede Aktion prueft die zugehoerige Permission. */
public final class OrderCommand implements CommandHandler {

  private static final List<String> SUBCOMMANDS =
      List.of(
          "help",
          "create",
          "mine",
          "market",
          "sell",
          "claims",
          "returns",
          "deliver",
          "cancel",
          "stats",
          "history",
          "favorites",
          "analytics");

  private final Services s;
  private final CreateFlow create;
  private final DeliveryFlow delivery;
  private final Menus menus;
  private final ParcelFlow claims;
  private final ParcelFlow returns;
  private final dev.orderhub.flow.InsightsFlow insights;
  private volatile dev.orderhub.flow.SellFlow sell;

  /** Verdrahtet {@code /order sell}. */
  public void sell(dev.orderhub.flow.SellFlow flow) {
    this.sell = flow;
  }

  public OrderCommand(
      Services services,
      CreateFlow create,
      DeliveryFlow delivery,
      Menus menus,
      ParcelFlow claims,
      ParcelFlow returns,
      dev.orderhub.flow.InsightsFlow insights) {
    this.s = services;
    this.create = create;
    this.delivery = delivery;
    this.menus = menus;
    this.claims = claims;
    this.returns = returns;
    this.insights = insights;
  }

  @Override
  public boolean canUse(@NotNull CommandSender sender) {
    return sender.hasPermission("orderhub.use");
  }

  @Override
  public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(s.messages().prefixed("general.players-only"));
      return true;
    }
    if (!player.hasPermission("orderhub.use")) {
      deny(player);
      return true;
    }
    if (args.length == 0) {
      openMain(player);
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "help" -> player.sendMessage(s.messages().prefixed("general.help"));
      case "create" -> {
        if (!need(player, "orderhub.create")) {
          return true;
        }
        if (args.length == 1) {
          create.start(player, null, null);
        } else if (args.length == 3) {
          create.start(player, args[1], args[2]);
        } else {
          player.sendMessage(s.messages().prefixed("general.usage-create"));
        }
      }
      case "mine" -> {
        if (need(player, "orderhub.create")) {
          menus.openMine(player);
        }
      }
      case "market" -> {
        if (need(player, "orderhub.market")) {
          openMarket(player, args);
        }
      }
      case "sell" -> {
        if (need(player, "orderhub.market")) {
          dev.orderhub.flow.SellFlow flow = sell;
          if (flow == null) {
            player.sendMessage(s.messages().prefixed("general.not-available"));
          } else {
            flow.start(player, args.length > 1 ? args[1] : null);
          }
        }
      }
      case "claims" -> menus.openClaims(player);
      case "returns" -> returns.claimAll(player);
      case "deliver" -> {
        if (need(player, "orderhub.market")) {
          withId(player, args, delivery::open);
        }
      }
      case "cancel" -> {
        if (need(player, "orderhub.create")) {
          withId(player, args, menus::cancel);
        }
      }
      case "stats" -> {
        if (need(player, "orderhub.stats")) {
          insights.showStats(player);
        }
      }
      case "history" -> {
        if (need(player, "orderhub.stats")) {
          insights.showHistory(player);
        }
      }
      case "favorites" -> {
        if (need(player, "orderhub.favorites")) {
          insights.favorites(player, args);
        }
      }
      case "analytics" -> {
        if (need(player, "orderhub.market")) {
          insights.analytics(player, args.length > 1 ? args[1] : null);
        }
      }
      default -> player.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
    return true;
  }

  /** {@code /order market [sortierung | suchtext...]}. */
  private void openMarket(Player player, String[] args) {
    if (args.length > 1 && isSort(args[1])) {
      menus.openMarket(
          player, new dev.orderhub.flow.MarketFlow.Filter(sortOf(args[1]), null, null));
      return;
    }
    String text =
        args.length > 1
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
            : null;
    menus.openMarket(
        player, new dev.orderhub.flow.MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, text));
  }

  private static boolean isSort(String raw) {
    try {
      OrderQuery.Sort.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private void openMain(Player player) {
    Map<MainMenu.Target, Consumer<Player>> handlers = new EnumMap<>(MainMenu.Target.class);
    handlers.put(MainMenu.Target.MARKET, p -> menus.openMarket(p, OrderQuery.Sort.NEWEST));
    handlers.put(MainMenu.Target.MINE, menus::openMine);
    handlers.put(
        MainMenu.Target.CREATE, p -> p.sendMessage(s.messages().prefixed("general.usage-create")));
    handlers.put(MainMenu.Target.CLAIMS, menus::openClaims);
    s.menus().open(player, new MainMenu(s.messages()::get, handlers));
  }

  private boolean need(Player player, String permission) {
    if (player.hasPermission(permission)) {
      return true;
    }
    deny(player);
    return false;
  }

  private void deny(Player player) {
    player.sendMessage(s.messages().prefixed("general.no-permission"));
  }

  private void withId(
      Player player, String[] args, java.util.function.BiConsumer<Player, Long> action) {
    if (args.length < 2 || !args[1].matches("[0-9]{1,18}")) {
      player.sendMessage(
          s.messages()
              .prefixed(
                  "general.invalid-number",
                  Messages.text("input", args.length < 2 ? "" : args[1])));
      return;
    }
    action.accept(player, Long.parseLong(args[1]));
  }

  private static OrderQuery.Sort sortOf(String raw) {
    if (raw == null) {
      return OrderQuery.Sort.NEWEST;
    }
    try {
      return OrderQuery.Sort.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      return OrderQuery.Sort.NEWEST;
    }
  }

  @Override
  public List<String> complete(@NotNull CommandSender sender, @NotNull String[] args) {
    if (!sender.hasPermission("orderhub.use")) {
      return List.of();
    }
    if (args.length == 1) {
      return filter(SUBCOMMANDS, args[0]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("market")) {
      List<String> sorts = new ArrayList<>();
      for (OrderQuery.Sort sort : OrderQuery.Sort.values()) {
        sorts.add(sort.name().toLowerCase(Locale.ROOT).replace('_', '-'));
      }
      return filter(sorts, args[1]);
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
      return filter(List.of("1", "16", "64", "576", "2304"), args[1]);
    }
    return List.of();
  }

  static List<String> filter(List<String> options, String prefix) {
    String p = prefix.toLowerCase(Locale.ROOT);
    return options.stream().filter(o -> o.startsWith(p)).toList();
  }
}
