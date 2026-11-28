plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.japicmp)
    alias(libs.plugins.spotbugs)
}

// Version der API, getrennt von der des Plugins (Semantic Versioning, siehe API.md).
group = "dev.orderhub"
version = "1.2.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    // Nur fuer Event, ItemStack und die Annotationen. Zur Laufzeit liefert der Server die Klassen.
    compileOnly(libs.paper.api)
    compileOnly(libs.spotbugs.annotations)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xwerror", "-quiet")
    }
}

// Kompatibilitaet: Die API darf gegenueber der letzten Version nicht binaer inkompatibel werden.
// Baseline ist die API aus dem Plugin 1.1.0 (orderhub-api/baseline), weil es davor kein eigenes
// API-Artefakt gab. Mit dem naechsten Release wird die Baseline auf dieses Release gesetzt.
val apiJar = tasks.jar
val compatibility by tasks.registering(me.champeau.gradle.japicmp.JapicmpTask::class) {
    description = "Fails the build on binary incompatible API changes against the last release."
    group = "verification"
    oldClasspath.from(files("baseline/orderhub-api-1.1.0.jar"))
    newClasspath.from(apiJar)
    onlyModified.set(true)
    failOnModification.set(true)
    onlyBinaryIncompatibleModified.set(true)
    // Mit onlyBinaryIncompatibleModified bricht failOnModification nur bei binaer inkompatiblen
    // Aenderungen (entfernte oder geaenderte Methoden und Typen), Erweiterungen sind erlaubt.
    ignoreMissingClasses.set(true)
    txtOutputFile.set(layout.buildDirectory.file("reports/japicmp.txt"))
    htmlOutputFile.set(layout.buildDirectory.file("reports/japicmp.html"))
    dependsOn(apiJar)
}
tasks.check { dependsOn(compatibility) }

spotbugs {
    toolVersion.set("4.10.5")
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    ignoreFailures.set(false)
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "orderhub-api"
            from(components["java"])
            pom {
                name.set("OrderHub API")
                description.set("Interfaces, records and events of the OrderHub plugin for Paper and Folia")
            }
        }
    }
    repositories {
        maven {
            // GitHub Packages. Der Zugang kommt aus der Umgebung (GITHUB_ACTOR, GITHUB_TOKEN).
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "OWNER/REPOSITORY"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
