package dev.orderhub.flow;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.item.ItemCodec;
import dev.orderhub.item.ItemCodecException;
import dev.orderhub.scheduler.PluginScheduler;
import dev.orderhub.storage.Storage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

/**
 * Persistente Rueckgabewarteschlange ({@code item_returns}). Wird benutzt, wenn Items einem Spieler
 * nicht direkt zurueckgegeben werden koennen (Inventar voll, Spieler weg, Plugin-Disable).
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Logger und Dienste sind gemeinsam genutzte Infrastruktur")
public final class ReturnQueue {

  private final Storage storage;
  private final PluginScheduler scheduler;
  private final ItemCodec codec;
  private final Logger logger;

  public ReturnQueue(Storage storage, PluginScheduler scheduler, ItemCodec codec, Logger logger) {
    this.storage = storage;
    this.scheduler = scheduler;
    this.codec = codec;
    this.logger = logger;
  }

  /** Speichert Stacks gleicher Art zusammengefasst. {@code sync} blockiert (nur beim Disable). */
  public void persist(UUID owner, List<ItemStack> stacks, boolean sync) {
    for (ItemStack stack : stacks) {
      if (stack == null || stack.isEmpty()) {
        continue;
      }
      persist(owner, stack, stack.getAmount(), sync);
    }
  }

  public void persist(UUID owner, ItemStack template, int amount, boolean sync) {
    ItemStack one = template.clone();
    one.setAmount(1);
    final ItemSnapshot snapshot;
    try {
      snapshot = codec.encode(one);
    } catch (ItemCodecException e) {
      // Nicht serialisierbare Items gehen nie still verloren: laut loggen, damit ein Admin
      // eingreift.
      logger.log(
          Level.SEVERE,
          "RETURN LOST item for " + owner + " amount " + amount + ": " + e.getMessage());
      return;
    }
    Runnable work =
        () -> {
          try {
            storage
                .transact(uow ->
                    uow.returns().add(owner, null, snapshot, amount, UUID.randomUUID(), uow.now()))
                .join();
          } catch (RuntimeException e) {
            // Nie still verlieren: Besitzer, Item und Menge stehen im Log, damit ein Admin
            // erstattet.
            logger.log(
                Level.SEVERE,
                "RETURN NOT SAVED owner="
                    + owner
                    + " item="
                    + snapshot.itemKey()
                    + " amount="
                    + amount,
                e);
          }
        };
    if (sync || !scheduler.isActive()) {
      work.run();
    } else {
      scheduler.runAsync(work);
    }
  }
}
