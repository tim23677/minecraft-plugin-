package dev.orderhub.bootstrap;

import dev.orderhub.tx.TransactionLog;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Schreibt die Zeilen des {@link TransactionLog} in eine eigene Datei mit Rotation: {@code
 * <ordner>/transactions.<n>.log}, hoechstens {@code count} Dateien zu je {@code limitBytes}. Die
 * neueste Datei hat die Nummer 0.
 *
 * @since 2.0.0
 */
public final class TransactionLogFile implements Closeable {

  private final FileHandler handler;

  public TransactionLogFile(Path dir, long limitBytes, int count) throws IOException {
    if (limitBytes < 1024 || count < 1) {
      throw new IllegalArgumentException("limitBytes must be >= 1024 and count >= 1");
    }
    Files.createDirectories(dir);
    this.handler =
        new FileHandler(
            dir.toAbsolutePath().toString().replace("%", "%%")
                + java.io.File.separator
                + "transactions.%g.log",
            (int) Math.min(Integer.MAX_VALUE, limitBytes),
            count,
            true);
    this.handler.setEncoding("UTF-8");
    this.handler.setFormatter(
        new Formatter() {
          @Override
          public String format(LogRecord record) {
            return record.getMessage() + System.lineSeparator();
          }
        });
  }

  /** Senke fuer {@link TransactionLog#install}. */
  public TransactionLog.Sink sink() {
    return line -> {
      LogRecord record = new LogRecord(java.util.logging.Level.INFO, line);
      handler.publish(record);
    };
  }

  @Override
  public void close() {
    handler.close();
  }
}
