plugins {
    java
}

group = "de.nucleus"
version = "0.1.0"
description = "Schlanker, modularer EssentialsX-Ersatz für Paper 26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    // VaultUnlocked (Nachfolger von Vault), nur die API
    maven("https://repo.codemc.io/repository/creatorfromhell/") {
        name = "vaultunlocked"
    }
}

dependencies {
    // Ab 26.1 nutzt Paper das Versionsschema "<version>.build.<N>-stable".
    // Gepinnt auf den Build des Testservers für reproduzierbare Builds.
    compileOnly("io.papermc.paper:paper-api:26.3.build.203-stable")
    // Optional: wird nur benutzt, wenn VaultUnlocked auf dem Server installiert ist (softdepend)
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Zum Prüfen der Sprachdateien (gleiche Version wie in Paper)
    testImplementation("org.yaml:snakeyaml:2.2")
}

java {
    // Paper 26.x benötigt Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        // Veraltete APIs sofort sichtbar machen
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform()
    }

    // Fester Dateiname: verhindert, dass im Server-Ordner pro Version eine weitere Jar landet
    jar {
        archiveFileName.set("Nucleus.jar")
    }

    // Kopiert die Jar in den gemeinsamen Testserver (andere Jars dort bleiben unberührt)
    register<Copy>("deploy") {
        dependsOn(jar)
        from(jar)
        into(System.getProperty("user.home") + "/Documents/code/testserver/plugins")
    }

    processResources {
        val props = mapOf("version" to project.version, "description" to project.description)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
