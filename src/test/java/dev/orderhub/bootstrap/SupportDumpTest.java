package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportDumpTest {

  @Test
  void secretKeysAreMaskedAndOtherValuesScrubbed() {
    Map<String, Object> cfg = new LinkedHashMap<>();
    cfg.put("storage.mariadb.password", "hunter2");
    cfg.put("storage.mariadb.password-env", "ORDERHUB_DB_PASSWORD");
    cfg.put("discord.webhook-url", "https://discord.com/api/webhooks/123/abc");
    cfg.put("updates.license-key", "AAAA-BBBB");
    cfg.put("storage.mariadb.jdbc-url", "jdbc:mariadb://dbuser:s3cret@10.0.0.5:3306/orderhub");
    cfg.put("server-id", "main");
    cfg.put("language", "de");
    cfg.put("owner", "11111111-2222-3333-4444-555555555555");
    String all = String.join("\n", SupportDump.configLines(cfg));
    assertThat(all)
        .doesNotContain("hunter2")
        .doesNotContain("s3cret")
        .doesNotContain("abc")
        .doesNotContain("AAAA-BBBB")
        .doesNotContain("10.0.0.5")
        .doesNotContain("11111111-2222")
        .contains("storage.mariadb.password: ***")
        .contains("server-id: main")
        .contains("language: de")
        .contains("<credentials>@<ip>")
        .contains("owner: <uuid>");
  }

  @Test
  void configLinesAreSorted() {
    var lines = SupportDump.configLines(Map.of("b", 1, "a", 2, "c", 3));
    assertThat(lines).containsExactly("a: 2", "b: 1", "c: 3");
  }

  @Test
  void onlyRecentOrderHubProblemsAreKeptWithTheirStackTraces() {
    List<String> log =
        List.of(
            "[12:00:00 INFO]: [OrderHub] enabled",
            "[12:00:01 WARN]: [OrderHub] payout slow for 11111111-2222-3333-4444-555555555555 from 192.168.1.20",
            "[12:00:02 ERROR]: [Other] boom",
            "[12:00:03 ERROR]: [OrderHub] storage failed",
            "java.sql.SQLException: x Exception",
            "\tat dev.orderhub.storage.Jdbc.update(Jdbc.java:1)",
            "Caused by: java.io.IOException",
            "\tat java.base/x.y.Z",
            "[12:00:04 INFO]: unrelated",
            "\tat dev.orderhub.not.part.of.trace");
    var lines = SupportDump.relevantLogLines(log, 50);
    assertThat(lines).hasSize(6);
    assertThat(String.join("\n", lines))
        .contains("payout slow")
        .contains("<uuid>")
        .contains("<ip>")
        .doesNotContain("192.168")
        .doesNotContain("[Other]")
        .contains("Caused by: java.io.IOException");
  }

  @Test
  void theLimitKeepsTheNewestLines() {
    List<String> log = new java.util.ArrayList<>();
    for (int i = 0; i < 100; i++) {
      log.add("[x WARN]: [OrderHub] problem " + i);
    }
    var lines = SupportDump.relevantLogLines(log, 10);
    assertThat(lines).hasSize(10);
    assertThat(lines.get(9)).contains("problem 99");
    assertThat(lines.get(0)).contains("problem 90");
  }

  @Test
  void composeNamesEverySectionAndSaysWhenThereAreNoProblems() {
    String text =
        SupportDump.compose(
            Map.of("version", "2.0.0 at 10.1.1.1"),
            List.of("Vault 1.7.3"),
            List.of("a: 1"),
            List.of());
    assertThat(text)
        .contains(
            "== Facts ==",
            "== Plugins ==",
            "== config.yml",
            "none found",
            "version: 2.0.0 at <ip>");
  }
}
