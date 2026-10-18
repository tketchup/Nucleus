plugins {
    java
}

group = "de.nucleus"
// Eigene Versionsnummer, unabhängig von Nucleus
version = "1.1.0"
description = "Zeigt Nucleus-Warps, -Spawns und Mobs auf der Pl3xMap"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.203-stable")
    compileOnly(project(":"))
    compileOnly(files("libs/Pl3xMap.jar"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    jar {
        archiveFileName.set("Nucleus-Pl3xMap.jar")
    }

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
