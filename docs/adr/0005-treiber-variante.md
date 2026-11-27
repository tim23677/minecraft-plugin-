# ADR 0005: Zwei Jars, mit und ohne eingebettete Datenbanktreiber

Status: angenommen, 2026-11-27

## Kontext

Das Standard-Jar lädt HikariCP, den SQLite- und den MariaDB-Treiber über `libraries` in `plugin.yml` beim ersten Start von Maven Central. Server ohne Zugang zu Maven Central, etwa hinter einer Firewall oder bei Hostern mit gesperrtem Ausgang, können das Plugin dann nicht starten. Das ist ein Verkaufshindernis. Die SpigotMC-Richtlinien für Premium-Ressourcen verlangen außerdem, dass eine Ressource direkt aus der heruntergeladenen Datei läuft (siehe `docs/marketplace-rules.md`).

## Entscheidung

Der Build erzeugt zwei Jars:

1. `OrderHub-<version>.jar`: klein (rund 0,6 MB), Bibliotheken kommen über `libraries` von Maven Central. Das ist der Standard.
2. `OrderHub-<version>-bundled.jar`: rund 13 MB, enthält HikariCP und den SQLite-Treiber. `plugin.yml` hat keinen Abschnitt `libraries`. HikariCP (`dev.orderhub.libs.hikari`) ist verschoben. Der SQLite-Treiber bleibt unverschoben, weil seine nativen Methoden an den Paketnamen `org.sqlite` gebunden sind.

Der MariaDB-Treiber (MariaDB Connector/J 4.0.0) steht unter der LGPL-2.1-oder-später. Ihn in ein proprietäres Jar einzubetten und dabei zu verschieben, würde die Pflicht erschweren, dass Anwender die Bibliothek austauschen können. Deshalb ist er nicht im gebündelten Jar. Folge: Das gebündelte Jar läuft ohne Maven Central nur mit SQLite. Für MariaDB hinter einer Firewall gibt es in 2.0.0 keine Lösung. Wer MariaDB nutzt, braucht beim ersten Start Zugriff auf Maven Central (Standard-Jar) oder muss `libraries` über einen Spiegel des Servers bedienen.

Beide Jars gehören ins Verkaufs-ZIP. Die Installationsanleitung nennt, wann welches zu nehmen ist.

## Offen und nicht geprüft

- Dass das gebündelte Jar auf einem echten Paper- und Folia-Server startet und Datenbankverbindungen aufbaut, ist hier nicht getestet (kein Serverlauf in dieser Umgebung, siehe RELEASE_REPORT.md). Nachholen mit `tools/mcstage/run.sh` und dem gebündelten Jar.
- Ob Paper selbst einen SQLite-Treiber auf dem Server-Classpath mitbringt und mit dem eingebetteten kollidiert, ist offen (siehe `tools/decisions/0003-jdbc-treiber-im-jar.md`).
- Wer das gebündelte Jar mit `storage.type: mariadb` startet, bekommt einen Fehler beim Treiberladen. Ob die Meldung verständlich ist, ist nicht geprüft.
