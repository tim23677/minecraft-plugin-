package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.money.Money;
import dev.orderhub.storage.InsightsRepository.TopStat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /order top [earned|spent|delivered|completed] [anzahl]}: Rangliste der Spieler. Die Zahlen
 * kommen aus der Statistik, die bei jeder abgeschlossenen Lieferung fortgeschrieben wird.
 *
 * @since 2.0.0
 */
public final class TopFlow {

  static final int DEFAULT_COUNT = 10;
  static final int MAX_COUNT = 25;

  private final Services s;
  private final Function<UUID, String> names;

  public TopFlow(Services services) {
    this(services, TopFlow::bukkitName);
  }

  TopFlow(Services services, Function<UUID, String> names) {
    this.s = services;
    this.names = names;
  }

  /** Kennzahlen als Befehlswoerter. */
  public static final List<String> STATS = List.of("earned", "spent", "delivered", "completed");

  public static String bukkitName(UUID id) {
    Player online = Bukkit.getPlayer(id);
    if (online != null) {
      return online.getName();
    }
    String name = Bukkit.getOfflinePlayer(id).getName();
    return name == null ? id.toString().substring(0, 8) : name;
  }

  /**
   * @param args die Argumente nach {@code top}
   */
  public void show(Player player, String[] args) {
    TopStat stat = TopStat.EARNED;
    int count = DEFAULT_COUNT;
    for (String arg : args) {
      if (arg.matches("[0-9]{1,3}")) {
        count = Math.max(1, Math.min(MAX_COUNT, Integer.parseInt(arg)));
      } else {
        try {
          stat = TopStat.valueOf(arg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          player.sendMessage(s.messages().prefixed("top.usage"));
          return;
        }
      }
    }
    TopStat chosen = stat;
    int shown = count;
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().top(chosen, shown),
        rows -> {
          if (rows.isEmpty()) {
            player.sendMessage(s.messages().prefixed("top.empty"));
            return;
          }
          player.sendMessage(
              s.messages()
                  .prefixed(
                      "top.header", Messages.text("stat", chosen.name().toLowerCase(Locale.ROOT))));
          int rank = 1;
          for (var row : rows) {
            player.sendMessage(
                s.messages()
                    .get(
                        "top.line",
                        Messages.number("rank", rank++),
                        Messages.text("player", names.apply(row.player())),
                        Messages.text("value", format(chosen, row.value()))));
          }
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  /** Geldkennzahlen mit zwei Nachkommastellen, Zaehler als ganze Zahl. */
  public static String format(TopStat stat, long value) {
    return stat == TopStat.EARNED || stat == TopStat.SPENT
        ? Money.format(value)
        : Long.toString(value);
  }
}
