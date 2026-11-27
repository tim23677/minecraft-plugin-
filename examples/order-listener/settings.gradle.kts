rootProject.name = "order-listener"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // Fuer lokale Versuche: ./gradlew :orderhub-api:publishToMavenLocal im OrderHub-Projekt.
        mavenLocal()
        // Veroeffentlichte API: GitHub Packages (Zugang mit Token) oder JitPack, siehe API.md.
    }
}
