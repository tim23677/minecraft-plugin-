package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the findings of the security review of 2.0.0. */
class ReviewFixesTest {

  @Test
  void dumpHidesCredentialsInJdbcQuery() {
    String out =
        SupportDump.scrub(
            "jdbc:mariadb://db.example.com/orderhub?user=oh&password=geheim&sslMode=verify-full");
    assertThat(out)
        .doesNotContain("geheim")
        .doesNotContain("user=oh")
        .contains("sslMode=verify-full");
  }

  @Test
  void dumpHidesIpv6() {
    assertThat(SupportDump.scrub("client 2001:db8::1 connected")).doesNotContain("2001:db8");
    assertThat(SupportDump.scrub("jdbc:mariadb://[2001:db8::5]:3306/x")).doesNotContain("2001:db8");
  }

  @Test
  void dumpHidesIpv6WithPortOrPrefix() {
    assertThat(SupportDump.scrub("db:2001:db8::5 down"))
        .doesNotContain("2001:db8")
        .doesNotContain("::5");
    assertThat(SupportDump.scrub("2001:db8:0:0:0:0:0:5:3306")).doesNotContain("2001:db8");
    assertThat(SupportDump.scrub("from ::1 ok")).doesNotContain("::1");
  }

  @Test
  void dumpKeepsTimestamps() {
    assertThat(SupportDump.scrub("[11:12:17 INFO]: ready")).isEqualTo("[11:12:17 INFO]: ready");
  }

  @Test
  void localHostIsParsedNotSearched() {
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://localhost:3306/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://127.0.0.1/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://[::1]:3306/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://db.example.com/x?a=//localhost"))
        .isFalse();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://localhost.evil.com/x")).isFalse();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://u:p@db.example.com/x")).isFalse();
  }

  @Test
  void logFileDirectoryWithPercentSignStaysWhereItIs(@TempDir Path temp) throws Exception {
    Path dir = temp.resolve("srv%h");
    try (TransactionLogFile file = new TransactionLogFile(dir, 4096, 2)) {
      file.sink().write("line");
    }
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> String.valueOf(p.getFileName())))
          .anyMatch(n -> n.startsWith("transactions."));
    }
  }

  @Test
  void tailReadsOnlyTheEndOfALargeFileAndDropsTheCutLine(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("latest.log");
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      text.append("line-").append(i).append('\n');
    }
    Files.writeString(log, text.toString());

    var lines = SupportDump.tailLines(log, 40);

    assertThat(lines).isNotEmpty().endsWith("line-100").doesNotContain("line-1", "line-50");
    assertThat(lines.get(0)).startsWith("line-");
    assertThat(lines.size()).isLessThan(10);
  }

  @Test
  void tailReturnsASmallFileCompletely(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("latest.log");
    Files.writeString(log, "a\nb\nc\n");

    assertThat(SupportDump.tailLines(log, 1024)).containsExactly("a", "b", "c");
  }

  @Test
  void tailOfAnEmptyFileIsEmpty(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("latest.log");
    Files.writeString(log, "");

    assertThat(SupportDump.tailLines(log, 1024)).isEmpty();
  }
}
