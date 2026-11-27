package dev.orderhub.storage;

import dev.orderhub.domain.OrderTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Auftragsvorlagen je Spieler.
 *
 * @since 2.0.0
 */
public interface TemplateRepository {

  /**
   * Speichert eine Vorlage oder ersetzt die gleichnamige desselben Spielers.
   *
   * @return {@code false}, wenn der Spieler das Limit erreicht hat und keine Vorlage gleichen
   *     Namens ersetzt wird
   */
  boolean save(OrderTemplate template, int maxPerPlayer);

  Optional<OrderTemplate> find(UUID owner, String name);

  List<OrderTemplate> list(UUID owner);

  boolean delete(UUID owner, String name);
}
