package dev.orderhub.storage;

import java.util.List;

/** Append-only Protokoll von Admin- und Recovery-Aktionen ({@code audit_log}). */
public interface AuditRepository {

  /**
   * @param actor Name oder "console"/"system", nie eine IP-Adresse
   * @param action Kurzbezeichnung, z. B. {@code order.cancel}
   * @param target betroffenes Objekt (Auftrags-Id, Journal-Id) oder {@code null}
   * @param reason Begruendung des Ausfuehrenden oder {@code null}
   */
  record Entry(
      String actor,
      String action,
      String target,
      String reason,
      String detail,
      String serverId,
      long createdAt) {}

  void append(Entry entry);

  /** Neueste zuerst. */
  List<Entry> recent(int limit);
}
