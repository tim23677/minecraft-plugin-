# ADR 0005: Zwei Jars, mit und ohne eingebettete Datenbanktreiber

Status: angenommen, 2026-11-27

## Kontext

Das Standard-Jar lädt HikariCP, den SQLite- und den MariaDB-Treiber über `libraries` in `plugin.yml` beim ersten Start von Maven Central. Server ohne Zugang zu Maven Central, etwa hinter einer Firewall oder bei Hostern mit gesperrtem Ausgang, können das Plugin dann nicht starten. Das ist ein Verkaufshindernis. Die SpigotMC-Richtlinien für Premium-Ressourcen verlangen außerdem, dass eine Ressource direkt aus der heruntergeladenen Datei läuft (siehe `docs/marketplace-rules.md`).

## Entscheidung

Der Build erzeugt zwei Jars:

1. `OrderHub-<version>.jar`: klein (rund 0,6 MB), Bibliotheken kommen über `libraries` von Maven Central. Das ist der Standard.
2. `OrderHub-<version>-bundled.jar`: rund 14 MB, enthält HikariCP, SQLite- und MariaDB-Treiber. `plugin.yml` hat keinen Abschnitt `libraries`. HikariCP (`dev.orderhub.libs.hikari`) und der MariaDB-Treiber (`dev.orderhub.libs.mariadb.jdbc`) sind verschoben. Der SQLite-Treiber bleibt unverschoben, weil seine nativen Methoden an den Paketnamen `org.sqlite` gebunden sind.

Beide Jars gehören ins Verkaufs-ZIP. Die Installationsanleitung nennt, wann welches zu nehmen ist.

## Offen und nicht geprüft

- Dass das gebündelte Jar auf einem echten Paper- und Folia-Server startet und Datenbankverbindungen aufbaut, ist hier nicht getestet (kein Serverlauf in dieser Umgebung, siehe RELEASE_REPORT.md). Nachholen mit `tools/mcstage/run.sh` und dem gebündelten Jar.
- Ob Paper selbst einen SQLite-Treiber auf dem Server-Classpath mitbringt und mit dem eingebetteten kollidiert, ist offen (siehe `tools/decisions/0003-jdbc-treiber-im-jar.md`).
- Die `META-INF/services/java.sql.Driver` des gebündelten Jars enthält nur den SQLite-Eintrag. Der MariaDB-Treiber wird nicht über den Service-Mechanismus, sondern über `setDriverClassName` mit dem verschobenen Klassennamen geladen. Die Zeichenkette im Bytecode von `DataSourceFactory` ist als `dev.orderhub.libs.mariadb.jdbc.Driver` nachgewiesen.
