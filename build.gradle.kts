plugins {
    id("net.fabricmc.fabric-loom") version "1.17.19"
    kotlin("jvm") version "2.4.0"
}

group = project.property("maven_group").toString()
version = project.property("mod_version").toString()

base {
    archivesName.set("${project.property("archives_base_name")}-mc${project.property("minecraft_version")}")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net") }
        filter {
            includeGroup("net.fabricmc")
            includeGroup("net.fabricmc.fabric-api")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")
}

// Java 25
sourceSets["main"].java.srcDir("src/main/java")

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
