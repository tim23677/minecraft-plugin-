import java.security.MessageDigest

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.pitest)
}

group = "dev.orderhub"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api) { exclude(group = "org.bukkit") }
    compileOnly(libs.placeholderapi)
    compileOnly(libs.spotbugs.annotations)

    implementation(libs.hikaricp)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.mariadb.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.paper.api)
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
    val props = mapOf("version" to project.version)
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

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "dev.orderhub.libs.hikari")
    relocate("org.slf4j", "dev.orderhub.libs.slf4j")
}
tasks.build { dependsOn(tasks.shadowJar) }

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
    threads.set(2)
    outputFormats.set(listOf("HTML"))
    timestampedReports.set(false)
}
