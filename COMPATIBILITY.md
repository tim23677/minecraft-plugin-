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

## Libraries loaded by Paper (not inside the jar)

The plugin jar (about 0.5 MB) contains only OrderHub itself. `plugin.yml` lists three libraries that Paper downloads from Maven Central on the first start into its `libraries` folder and loads in an isolated class loader of this plugin:

| Library | Version |
|---|---|
| HikariCP | 7.1.0 |
| sqlite-jdbc | 3.54.1.0 |
| mariadb-java-client | 4.0.0 |

Because the libraries live in OrderHub's own class loader, they do not conflict with drivers of other plugins. The server needs internet access to Maven Central for the first start (offline servers can copy the `libraries` folder from another server). SLF4J is provided by Paper itself, so no SLF4J warnings appear.

Checked on a real server: Paper 1.21.11 and Folia 1.21.11 download the libraries, enable the plugin and show no ERROR lines (`tools/mcstage/run.sh`). Not checked: a second plugin that bundles a different JDBC driver version.

## Economy plugins

| Plugin | Status |
|---|---|
| EssentialsX 2.22.0 with VaultUnlocked 2.20.3 on Paper 1.21.11 | full player scenario with two bots passed (`tools/e2e/e2e.js`) |
| any other Vault economy (CMI and others), Folia with an economy | not tested |

Vault calls run on the global server thread (`economy.async-safe: false`).

## Provided by the server, not bundled

Paper API, Adventure and MiniMessage (part of Paper), Vault API 1.7.1 (soft dependency), PlaceholderAPI 2.12.3 (soft dependency).

## Test only

JUnit Jupiter 6.0.3, MockBukkit 4.116.3, AssertJ 3.27.7, Testcontainers 2.0.5. JUnit 6 is used instead of 5 because MockBukkit 4.116.3 requires it.

## Databases

| Database | Status |
|---|---|
| SQLite | tested (unit tests, load test with 100,000 orders: p50 20 ms, p95 200 ms, maximum 1,259 ms for 480 parallel searches on 8 threads) |
| MariaDB 11.4 | tested through Testcontainers (49 integration tests including both migrations dialects) |
| MariaDB 10.11 | tested in an earlier build only, not repeated for 1.0.0 |
| MySQL | not supported. OrderHub stops with `MySQL is not supported` |

Schema changes are versioned migrations V1 to V5 and run once at start against SQLite and MariaDB. Before a migration on an existing SQLite database OrderHub copies the database to `backups/`.

## Dependency vulnerability scan

`./gradlew dependencyCheckAnalyze` needs an `NVD_API_KEY`. No key was available when 1.0.0 was built, so the scan was not run. The CI job `dependency-check` runs it when the repository secret exists.

## Results of 2.0.0 (measured in this build)

| Measurement | Result |
|---|---|
| SQLite, 100,000 orders, 480 parallel queries on 8 threads | p50 17 ms, p95 153 ms, max 1,782 ms (insert 7,568 orders/s) |
| MariaDB 11.4, 100,000 orders, two simulated servers, 480 queries | offset page p50 4 / p95 19 ms, keyset page p50 4 / p95 8 ms, text search p50 44 / p95 96 ms, category filter p50 4 / p95 11 ms (insert 1,578 orders/s) |
| Two servers delivering to the same order, crash with recovery on the other server | covered by integration tests against MariaDB 11.4 |
| Upgrade from a real 1.1.0 database | `UpgradeFrom110Test` |

## Languages

en, de, fr, es, pl, pt-br, ru, tr, zh-cn. A test checks keys and placeholders of every file against English. The seven new languages were not reviewed by native speakers.

## Not tested

| Item | Status |
|---|---|
| Folia bot play-through of 2.0.0 | not run (Paper: 17 of 17 checks, Paper and Folia start via mcstage: 2 passed) |
| Economy plugins other than EssentialsX with VaultUnlocked, Folia with an economy | not tested |
| Geyser and Bedrock clients | not tested |
| PlaceholderAPI on a live server | unit tests only |
| `OrderHub-<version>-bundled.jar` loaded on a server | built and content checked, not started on a server |
| `dependencyCheckAnalyze` | not run, needs `NVD_API_KEY` |
| PIT mutation thresholds per package | not reached, see RELEASE_REPORT.md |
