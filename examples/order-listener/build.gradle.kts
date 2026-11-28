plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Nur die API, nicht das Plugin. Zur Laufzeit liefert das installierte OrderHub die Klassen.
    compileOnly("dev.orderhub:orderhub-api:1.1.0")
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
