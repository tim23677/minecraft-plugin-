package dev.orderhub.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Minimale, dialektneutrale Schema-Migration. Wird durch ein echtes Tool ersetzt, sobald noetig.
 */
public final class Migrations {

  private Migrations() {}

  public static void apply(DataSource dataSource) throws SQLException {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS orders ("
              + "id BIGINT PRIMARY KEY, "
              + "owner VARCHAR(36) NOT NULL, "
              + "created_at BIGINT NOT NULL)");
    }
  }
}
