plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6"
    // NOTE: `runServer` is currently unusable. 2.x resolves servers through PaperMC's v2
    // API, which now returns 403 ("Unknown Paper Version"), and every 3.x release that
    // speaks the v3 API requires Gradle 9. Use scripts/dev-server.sh until the wrapper is
    // upgraded; it resolves servers the same way .github/scripts/smoke-test.sh does.
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.ninja6.spiralgenesis"
version = project.findProperty("pluginVersion")?.toString() ?: "1.0.0-SNAPSHOT"
description = "Deterministic Square Spiral Genesis & Territory Allocation Engine for PaperMC"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    // PaperMC repository
    maven("https://repo.papermc.io/repository/maven-public/")
    // GeyserMC / Floodgate repository
    maven("https://repo.opencollab.dev/main/")
    // AuthMe-Reloaded repository
    maven("https://repo.codemc.io/repository/maven-public/")
    // Sonatype snapshots fallback
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    // PaperMC Server API (1.20.4 target, runtime compatible with 1.20.x - 1.21.x)
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    // Floodgate API for Bedrock client detection (Soft-dependency)
    compileOnly("org.geysermc.floodgate:api:2.2.2-SNAPSHOT")

    // AuthMe-Reloaded API for Java authentication gating (Soft-dependency)
    compileOnly("fr.xephi:authme:5.6.0-SNAPSHOT")

    // Unit Testing
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // compileOnly dependencies are not inherited by the test compile classpath.
    // YamlConfiguration runs standalone (no server instance), so this is enough to
    // cover configuration parsing and validation.
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    // In-process Bukkit server mock, for exercising allocation against a real World.
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.93.2")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf(
            "name" to rootProject.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "1.20"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    javadoc {
        // Prose docs are intentional here; missing @param/@return on self-describing
        // accessors should not flood the build log.
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    shadowJar {
        archiveBaseName.set("SpiralGenesis")
        archiveClassifier.set("")
        // No runtime dependencies are bundled (every dependency is compileOnly),
        // so minimize() would have nothing to strip and only risks removing
        // classes that are resolved reflectively.
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.20.4")
    }
}
