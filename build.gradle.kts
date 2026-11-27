import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.pitest)
    alias(libs.plugins.cyclonedx)
}

group = "dev.orderhub"
version = "2.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // Die API liegt im eigenen Modul und wird mit ins Plugin-Jar gepackt, damit andere Plugins sie
    // ueber den Klassenlader von OrderHub finden.
    implementation(project(":orderhub-api"))
    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api) { exclude(group = "org.bukkit") }
    compileOnly(libs.placeholderapi)
    compileOnly(libs.spotbugs.annotations)

    // Zur Laufzeit liefert Paper diese Bibliotheken (plugin.yml "libraries", Maven Central) bzw.
    // sein eigenes slf4j. Das Jar bleibt klein, Treiber kollidieren nicht mit anderen Plugins.
    compileOnly(libs.hikaricp)
    compileOnly(libs.slf4j.api)
    // bStats wird eingebettet und verschoben (shadowJar), damit es nie mit anderen Plugins kollidiert.
    implementation(libs.bstats.bukkit)

    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.paper.api)
    testImplementation(libs.hikaricp)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
    testCompileOnly(libs.spotbugs.annotations)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.mariadb.jdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    val props =
        mapOf(
            "version" to project.version,
            "hikariVersion" to libs.versions.hikari.get(),
            "sqliteVersion" to libs.versions.sqlite.get(),
            "mariadbVersion" to libs.versions.mariadb.get(),
        )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "load")
    }
    testLogging { events("passed", "skipped", "failed") }
}

// Tests mit echter Datenbank (Testcontainers). Brauchen einen laufenden Docker-Daemon.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs tests tagged 'integration' (Testcontainers, needs Docker)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
    testLogging { events("passed", "skipped", "failed") }
}

// Reproduzierbarer Lasttest (100.000 Auftraege auf SQLite). Ergebnis: build/reports/load.txt
val loadTest by tasks.registering(Test::class) {
    description = "Runs tests tagged 'load' (100k orders, parallel searches)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("load") }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

// Reproduzierbare Archive: feste Zeitstempel und feste Dateireihenfolge, damit zwei Builds
// desselben Quellstands dasselbe Jar ergeben (gleiche SHA-256).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "dev.orderhub.libs.bstats")
}
// Variante mit eingebetteten Bibliotheken (HikariCP, SQLite-Treiber) fuer Server ohne Zugriff auf
// Maven Central. plugin.yml dieser Variante hat keinen "libraries"-Abschnitt. HikariCP wird
// verschoben. Der SQLite-Treiber bleibt unverschoben, weil seine nativen Methoden an den
// Paketnamen gebunden sind. Der MariaDB-Treiber (LGPL-2.1-or-later) wird bewusst nicht in ein
// proprietaeres Jar eingebettet, siehe docs/adr/0005-treiber-variante.md.
val bundledDrivers by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    bundledDrivers(libs.hikaricp)
    bundledDrivers(libs.sqlite.jdbc)
}

val bundledPluginYml by tasks.registering {
    description = "Writes plugin.yml without the libraries section for the bundled variant."
    dependsOn(tasks.processResources)
    val source = layout.buildDirectory.file("resources/main/plugin.yml")
    val target = layout.buildDirectory.file("bundled/plugin.yml")
    inputs.file(source)
    outputs.file(target)
    doLast {
        val kept = mutableListOf<String>()
        var inLibraries = false
        for (line in source.get().asFile.readLines()) {
            if (line.startsWith("libraries:")) {
                inLibraries = true
                continue
            }
            if (inLibraries && line.startsWith("  - ")) {
                continue
            }
            inLibraries = false
            kept += line
        }
        target
            .get()
            .asFile.parentFile
            .mkdirs()
        target.get().asFile.writeText(kept.joinToString("\n") + "\n")
    }
}

val bundledJar by tasks.registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    description = "Plugin jar with embedded database drivers (no download from Maven Central)."
    group = "build"
    archiveClassifier.set("bundled")
    from(sourceSets.main.get().output) { exclude("plugin.yml") }
    from(bundledPluginYml)
    configurations = listOf(project.configurations.runtimeClasspath.get(), bundledDrivers)
    mergeServiceFiles()
    relocate("org.bstats", "dev.orderhub.libs.bstats")
    relocate("com.zaxxer.hikari", "dev.orderhub.libs.hikari")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
// SHA-256-Pruefsummen der Jars im Verkaufs-ZIP.
val salesChecksums by tasks.registering {
    description = "Writes SHA256SUMS for the jars of the sales ZIP."
    group = "distribution"
    dependsOn(tasks.shadowJar, bundledJar, ":orderhub-api:jar", ":orderhub-api:javadocJar")
    val out = layout.buildDirectory.file("sales/SHA256SUMS")
    val jars =
        files(
            tasks.shadowJar.flatMap { it.archiveFile },
            bundledJar.flatMap { it.archiveFile },
            project(":orderhub-api").tasks.named("jar"),
            project(":orderhub-api").tasks.named("javadocJar"),
        )
    inputs.files(jars)
    outputs.file(out)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        val lines =
            jars.files.sortedBy { it.name }.map { f ->
                val hex = digest.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                "$hex  ${f.name}"
            }
        out
            .get()
            .asFile.parentFile
            .mkdirs()
        out.get().asFile.writeText(lines.joinToString("\n") + "\n")
    }
}

// Verkaufs-ZIP: nur Auslieferungsdateien. Intern bleiben .claude/, tools/, Berichte, Plaene,
// Sicherheitsanalyse, Quellcode und Tests.
val salesZip by tasks.registering(Zip::class) {
    description = "Builds the sales ZIP (jars, default configuration, README, changelog, licenses)."
    group = "distribution"
    dependsOn(tasks.shadowJar, bundledJar, ":orderhub-api:jar", ":orderhub-api:javadocJar", salesChecksums)
    archiveFileName.set("OrderHub-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    val root = "OrderHub-${project.version}"
    into(root) {
        from(tasks.shadowJar)
        from(bundledJar)
        from(project(":orderhub-api").tasks.named("jar"))
        from(project(":orderhub-api").tasks.named("javadocJar"))
        from("README.md", "CHANGELOG.md", "LICENSE.txt", "THIRD_PARTY_NOTICES.md")
        from(layout.buildDirectory.file("sales/SHA256SUMS"))
        from("src/main/resources") {
            include("config.yml", "menus.yml", "discord.yml", "messages_*.yml")
            into("config")
        }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Prueft den Inhalt des Verkaufs-ZIPs: erlaubte Dateien, Pflichtdateien, keine internen Dateien und
// Drittanbieter-Hinweis fuer jede eingebettete Bibliothek.
val verifySalesZip by tasks.registering {
    description = "Fails if the sales ZIP contains anything but delivery files or lacks required ones."
    group = "verification"
    dependsOn(salesZip)
    val zipFile = salesZip.flatMap { it.archiveFile }
    val version = project.version.toString()
    doLast {
        val root = "OrderHub-$version/"
        val names = mutableListOf<String>()
        ZipFile(zipFile.get().asFile).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory }
                .forEach { names += it.name }
        }
        val allowed =
            listOf(
                Regex("""OrderHub-$version/OrderHub-$version\.jar"""),
                Regex("""OrderHub-$version/OrderHub-$version-bundled\.jar"""),
                Regex("""OrderHub-$version/orderhub-api-[0-9.]+\.jar"""),
                Regex("""OrderHub-$version/orderhub-api-[0-9.]+-javadoc\.jar"""),
                Regex("""OrderHub-$version/(README\.md|CHANGELOG\.md|LICENSE\.txt|THIRD_PARTY_NOTICES\.md|SHA256SUMS)"""),
                Regex("""OrderHub-$version/config/(config|menus|discord)\.yml"""),
                Regex("""OrderHub-$version/config/messages_[a-z]{2}(-[A-Za-z]{2})?\.yml"""),
            )
        val unexpected = names.filter { n -> allowed.none { it.matches(n) } }
        check(unexpected.isEmpty()) { "Unexpected files in the sales ZIP: $unexpected" }
        val required =
            listOf(
                "OrderHub-$version.jar",
                "OrderHub-$version-bundled.jar",
                "README.md",
                "CHANGELOG.md",
                "LICENSE.txt",
                "THIRD_PARTY_NOTICES.md",
                "SHA256SUMS",
                "config/config.yml",
                "config/menus.yml",
                "config/messages_en.yml",
                "config/messages_de.yml",
            )
        val missing = required.filter { (root + it) !in names }
        check(missing.isEmpty()) { "Missing files in the sales ZIP: $missing" }
        check(names.any { Regex("""$root""" + """orderhub-api-[0-9.]+\.jar""").matches(it) }) { "API jar missing" }
        val forbidden = listOf(".claude", "tools/", "RELEASE_REPORT", "PLAN.md", "SPEC.md", "TESTPLAN.md", "SECURITY.md", "src/", ".github")
        val leaked = names.filter { n -> forbidden.any { n.contains(it) } }
        check(leaked.isEmpty()) { "Internal files in the sales ZIP: $leaked" }
        // Jede eingebettete Bibliothek muss im Drittanbieter-Hinweis stehen.
        val notices =
            ZipFile(zipFile.get().asFile).use { zip ->
                zip.getInputStream(zip.getEntry(root + "THIRD_PARTY_NOTICES.md")).bufferedReader().readText()
            }
        val embeddedChecks =
            mapOf(
                "com/zaxxer" to "HikariCP",
                "dev/orderhub/libs/hikari" to "HikariCP",
                "org/sqlite" to "SQLite JDBC",
                "dev/orderhub/libs/bstats" to "bStats",
            )
        for (jarName in listOf("OrderHub-$version.jar", "OrderHub-$version-bundled.jar")) {
            ZipFile(zipFile.get().asFile).use { outer ->
                val entry = outer.getEntry(root + jarName)
                ZipInputStream(outer.getInputStream(entry)).use { jar ->
                    var e = jar.nextEntry
                    val seen = mutableSetOf<String>()
                    while (e != null) {
                        embeddedChecks.forEach { (prefix, library) ->
                            if (e!!.name.startsWith(prefix)) seen += library
                        }
                        check(!e!!.name.contains(".claude") && !e!!.name.startsWith("tools/")) {
                            "Internal file inside $jarName: ${e!!.name}"
                        }
                        e = jar.nextEntry
                    }
                    seen.forEach { library ->
                        check(notices.contains(library)) { "$library is embedded in $jarName but not listed in THIRD_PARTY_NOTICES.md" }
                    }
                }
            }
        }
        println("Sales ZIP ok: ${names.size} files")
    }
}

tasks.build { dependsOn(tasks.shadowJar, bundledJar, ":orderhub-api:build", verifySalesZip) }

// Kopiert das fertige Plugin-Jar nach release/ und schreibt die SHA-256-Pruefsumme.
val release by tasks.registering {
    description = "Copies the shadow jar to release/ and writes SHA256SUMS."
    group = "distribution"
    dependsOn(tasks.shadowJar)
    doLast {
        val jar =
            tasks.shadowJar
                .get()
                .archiveFile
                .get()
                .asFile
        val dir = layout.projectDirectory.dir("release").asFile
        dir.mkdirs()
        val target = File(dir, jar.name)
        jar.copyTo(target, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        File(dir, "SHA256SUMS").writeText("$hex  ${target.name}\n")
    }
}
tasks.jar { archiveClassifier.set("plain") }

spotless {
    java {
        target("src/**/*.java", "orderhub-api/src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

spotbugs {
    toolVersion.set("4.10.5")
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    ignoreFailures.set(false)
}
tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    // Der NVD-Schluessel kommt aus der Umgebung, niemals aus dem Repository.
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey = it }
    analyzers.assemblyEnabled = false
}

pitest {
    junit5PluginVersion.set(libs.versions.pitestJunit5)
    pitestVersion.set(libs.versions.pitest)
    targetClasses.set(listOf("dev.orderhub.*"))
    // Nur Testklassen als Tests: Ohne diese Angabe hielt PIT Hauptklassen mit Vault-Abhaengigkeit fuer Tests.
    targetTests.set(listOf("dev.orderhub.*Test", "dev.orderhub.*Tests"))
    excludedGroups.set(listOf("integration"))
    threads.set(2)
    outputFormats.set(listOf("HTML"))
    timestampedReports.set(false)
}
