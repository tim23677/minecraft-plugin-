package dev.orderhub.flow;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.storage.AuditRepository;
import dev.orderhub.storage.InsightsRepository.TopStat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stellt die Rangliste als Discord-Nachricht ein. Wird von {@code /orderadmin discord-top}
 * ausgeloest, mit Bestaetigung und Grund im Audit-Protokoll. Blockiert (Datenbank), nie auf einem
 * Minecraft-Thread aufrufen.
 *
 * @since 2.0.0
 */
public final class TopReport {

  private final Services s;
  private final Function<UUID, String> names;

  public TopReport(Services services) {
    this(services, TopFlow::bukkitName);
  }

  TopReport(Services services, Function<UUID, String> names) {
    this.s = services;
    this.names = names;
  }

  /**
   * @return {@code false}, wenn Discord oder dieses Ereignis ausgeschaltet ist, nichts wurde
   *     eingereiht
   */
  public boolean publish(TopStat stat, int count, String actor, String reason) {
    if (!s.discord().wants(DiscordService.Event.TOP_TRADERS)) {
      return false;
    }
    List<dev.orderhub.storage.InsightsRepository.TopRow> rows = s.insights().top(stat, count);
    StringBuilder body = new StringBuilder();
    int rank = 1;
    for (var row : rows) {
      body.append(rank++)
          .append(". ")
          .append(names.apply(row.player()))
          .append(" - ")
          .append(TopFlow.format(stat, row.value()))
          .append('\n');
    }
    if (rows.isEmpty()) {
      body.append("No trades yet.");
    }
    s.discord()
        .publish(
            DiscordService.Event.TOP_TRADERS,
            "Top players by " + stat.name().toLowerCase(Locale.ROOT),
            body.toString());
    s.storage()
        .transact(uow -> {
          uow.audit()
              .append(
                  new AuditRepository.Entry(
                      actor,
                      "discord.top",
                      stat.name().toLowerCase(Locale.ROOT),
                      reason,
                      "rows=" + rows.size(),
                      s.storage().serverId(),
                      uow.now()));
          return null;
        })
        .join();
    return true;
  }
}
