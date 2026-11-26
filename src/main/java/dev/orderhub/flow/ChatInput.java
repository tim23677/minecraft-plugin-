package dev.orderhub.flow;

import dev.orderhub.scheduler.PluginScheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Faengt die naechste Chatzeile eines Spielers ab (Mengen- und Preiseingabe). Die Eingabe ist
 * reiner Text, nie Item-Daten oder Befehle. Abbruchwort und Zeitlimit sind konfigurierbar.
 */
public final class ChatInput implements Listener {

  private record Pending(Consumer<String> onText, Runnable onCancel, long expiresAtMillis) {}

  private final PluginScheduler scheduler;
  private final java.util.function.Supplier<String> cancelWord;
  private final java.util.function.LongSupplier clock;
  private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

  public ChatInput(
      PluginScheduler scheduler,
      java.util.function.Supplier<String> cancelWord,
      java.util.function.LongSupplier clock) {
    this.scheduler = scheduler;
    this.cancelWord = cancelWord;
    this.clock = clock;
  }

  /** Wartet auf die naechste Chatzeile. Eine vorherige offene Eingabe wird abgebrochen. */
  public void await(Player player, int timeoutSeconds, Consumer<String> onText, Runnable onCancel) {
    Pending previous =
        pending.put(
            player.getUniqueId(),
            new Pending(onText, onCancel, clock.getAsLong() + timeoutSeconds * 1000L));
    if (previous != null) {
      scheduler.tryRunOn(player, previous.onCancel());
    }
  }

  public boolean isWaiting(UUID player) {
    return pending.containsKey(player);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    Pending p = pending.remove(player.getUniqueId());
    if (p == null) {
      return;
    }
    event.setCancelled(true);
    String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    boolean expired = clock.getAsLong() > p.expiresAtMillis();
    if (expired || text.equalsIgnoreCase(cancelWord.get())) {
      scheduler.tryRunOn(player, p.onCancel());
      return;
    }
    scheduler.tryRunOn(player, () -> p.onText().accept(text));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    pending.remove(event.getPlayer().getUniqueId());
  }
}
