package dev.orderhub.storage;

/** Unterstuetzte SQL-Dialekte. */
public enum Dialect {
  SQLITE,
  MARIADB;

  /** Ob {@code SELECT ... FOR UPDATE} unterstuetzt wird (SQLite sperrt ueber BEGIN IMMEDIATE). */
  public boolean supportsRowLocks() {
    return this == MARIADB;
  }
}
