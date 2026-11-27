import java.security.MessageDigest

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
version = "1.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
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
// Variante mit eingebetteten Treibern (HikariCP, SQLite, MariaDB) fuer Server ohne Zugriff auf
// Maven Central. plugin.yml dieser Variante hat keinen "libraries"-Abschnitt. HikariCP und der
// MariaDB-Treiber werden verschoben. Der SQLite-Treiber bleibt unverschoben, weil seine nativen
// Methoden an den Paketnamen gebunden sind.
val bundledDrivers by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    bundledDrivers(libs.hikaricp)
    bundledDrivers(libs.sqlite.jdbc)
    bundledDrivers(libs.mariadb.jdbc)
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
    relocate("org.mariadb.jdbc", "dev.orderhub.libs.mariadb.jdbc")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
tasks.build { dependsOn(tasks.shadowJar, bundledJar) }

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
        target("src/**/*.java")
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
