# Compatibility and dependencies

## Platform

| Item | Value |
|---|---|
| Java | 21 |
| Paper API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` (a snapshot, builds are not bit for bit reproducible) |
| Server | Paper and Folia 1.21.x (plugin descriptor `api-version: 1.21`, `folia-supported: true`) |
| Not supported | Spigot, Bukkit, NMS, Minecraft versions before 1.21 |

Paper 26.x needs Java 25 and was deliberately not targeted, see tools/JAVA_TOOLCHAIN.md.

Folia: all tasks go through `PluginScheduler` (global, region, entity and async schedulers on Folia, Bukkit scheduler on Paper). The Folia code paths were reviewed but not run on a real Folia server in this build.

## Bundled libraries (inside the jar)

| Library | Version | Relocated |
|---|---|---|
| HikariCP | 7.1.0 | yes, `dev.orderhub.libs.hikari` |
| SLF4J API | 2.0.17 | yes, `dev.orderhub.libs.slf4j` |
| sqlite-jdbc | 3.54.1.0 | no (native libraries and JNI names depend on the package) |
| mariadb-java-client | 4.0.0 | no |

The JDBC drivers are not relocated. If another plugin bundles the same drivers in a different version, class loading conflicts are possible. This was not tested.

## Provided by the server, not bundled

Paper API, Adventure and MiniMessage (part of Paper), Vault API 1.7.1 (soft dependency), PlaceholderAPI 2.12.3 (soft dependency).

## Test only

JUnit Jupiter 6.0.3, MockBukkit 4.116.3, AssertJ 3.27.7, Testcontainers 2.0.5. JUnit 6 is used instead of 5 because MockBukkit 4.116.3 requires it.

## Databases

SQLite (bundled driver) and MariaDB 10.11 (tested). MySQL is not tested, the migrations use `CREATE INDEX IF NOT EXISTS`, which MySQL 8 does not support.
