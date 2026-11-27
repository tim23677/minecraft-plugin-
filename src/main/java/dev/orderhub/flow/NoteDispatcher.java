package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.storage.InsightsRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * Stellt Hinweise zu, waehrend der Spieler schon online ist. Das gilt vor allem fuer Hinweise, die
 * ein anderer Server in der gemeinsamen Datenbank angelegt hat (Lieferung auf Server A,
 * Auftraggeber auf Server B). Laeuft zeitgesteuert, die Datenbankarbeit auf einem Worker-Thread,
 * die Anzeige im Entity-Kontext des Spielers.
 *
 * @since 2.0.0
 */
public final class NoteDispatcher {

  private static final int LIMIT = 200;

  private final Services s;
  private final Supplier<Collection<? extends Player>> online;

  public NoteDispatcher(Services services, Supplier<Collection<? extends Player>> online) {
    this.s = services;
    this.online = online;
  }

  /** Ein Durchlauf. Darf nicht auf einem Entity-Thread laufen (Datenbankzugriff). */
  public void runOnce() {
    Map<UUID, Player> byId = new java.util.HashMap<>();
    for (Player p : online.get()) {
      byId.put(p.getUniqueId(), p);
    }
    if (byId.isEmpty()) {
      return;
    }
    List<InsightsRepository.Note> notes;
    try {
      notes = s.insights().takeNotesForOnline(byId.keySet(), LIMIT);
    } catch (RuntimeException e) {
      s.logger().log(Level.FINE, "note dispatch failed, will retry", e);
      return;
    }
    Map<UUID, List<InsightsRepository.Note>> perPlayer = new java.util.HashMap<>();
    for (InsightsRepository.Note n : notes) {
      perPlayer.computeIfAbsent(n.player(), k -> new java.util.ArrayList<>()).add(n);
    }
    perPlayer.forEach((id, list) -> {
      Player player = byId.get(id);
      if (player == null) {
        return;
      }
      // Verschwindet der Spieler jetzt, ist der Hinweis trotzdem beansprucht und geht verloren:
      // Er ist nur eine Anzeige, die zugrunde liegende Lieferung bleibt als Paket erhalten.
      s.scheduler().tryRunOn(player, () -> show(player, list));
    });
  }

  private void show(Player player, List<InsightsRepository.Note> notes) {
    LoginSummary summary = LoginSummary.of(notes);
    if (summary.isEmpty()) {
      return;
    }
    var settings = s.settings().get();
    Notifier.Display display = Notifier.Display.parse(settings.notificationDisplay());
    if (summary.deliveries() > 0 || summary.expiredOrders() > 0) {
      Notifier.show(
          player,
          s.messages()
              .prefixed(
                  "notify.live-summary",
                  Messages.number("items", summary.deliveredItems()),
                  Messages.number("deliveries", summary.deliveries()),
                  Messages.number("expired", summary.expiredOrders())),
          display,
          settings.notificationSound());
    }
    for (LoginSummary.Alert alert : summary.alerts()) {
      Notifier.show(
          player,
          s.messages()
              .prefixed(
                  "notify.price-alert",
                  Messages.text("item", alert.item()),
                  Messages.text("quantity", alert.quantity()),
                  Messages.text("unit", alert.unit()),
                  Messages.text("id", alert.orderId())),
          display,
          "");
    }
  }
}
