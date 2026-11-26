package dev.orderhub.gui;

import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Persistente Rueckgabe fuer Items, die nicht direkt an den Spieler zurueckgehen koennen
 * (Disconnect, Kick, Tod, volles Inventar, Plugin-Disable ausserhalb des Entity-Kontexts). Vom
 * Aufrufer gestellt, z. B. als Eintrag in der Rueckgabewarteschlange.
 *
 * <p>Wird im Entity-Kontext des Spielers aufgerufen (Ausnahme: Notpfad beim Plugin-Disable). Der
 * Aufruf muss die Items durable uebernehmen oder eine Exception werfen; die uebergebenen Stacks
 * gehoeren der Senke.
 */
@FunctionalInterface
public interface ReturnSink {

  void returnItems(UUID playerId, List<ItemStack> items);
}
