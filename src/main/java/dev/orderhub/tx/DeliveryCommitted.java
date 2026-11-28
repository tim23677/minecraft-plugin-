package dev.orderhub.tx;

import java.util.UUID;

/**
 * Ereignis einer festgeschriebenen Lieferung, Grundlage fuer price_history, Statistik und
 * Benachrichtigungen. Die Daten sind aus Journal und Auftrag jederzeit rekonstruierbar.
 */
public record DeliveryCommitted(
    UUID journalId,
    long orderId,
    String itemKey,
    UUID supplier,
    UUID owner,
    int quantity,
    long unitPrice,
    long gross,
    long salesTax,
    long netPayout,
    boolean orderCompleted,
    long committedAt,
    String serverId) {}
