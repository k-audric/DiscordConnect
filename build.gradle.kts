import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    kotlin("jvm") version "2.5.0-Beta2"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val kotlinVersion = getKotlinPluginVersion()

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.slf4j")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")

        val libs = "com.kaudric.discordconnect.libs"
        relocate("net.dv8tion.jda", "$libs.jda")
        listOf(
            "com.fasterxml.jackson",
            "com.neovisionaries.ws",
            "gnu.trove",
            "okhttp3",
            "okio",
            "org.apache.commons.collections4",
        ).forEach { relocate(it, "$libs.$it") }

        mergeServiceFiles()
        filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version, "kotlinVersion" to kotlinVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
