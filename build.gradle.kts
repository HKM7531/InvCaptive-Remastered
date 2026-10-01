import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
}

group = property("group").toString()
version = property("version").toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    // kotlin stdlib is bundled into the plugin jar (see jar task) so no external Kotlin plugin is needed
    implementation(kotlin("stdlib"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks {
    processResources {
        val props = mapOf(
            "pluginName" to project.property("pluginName"),
            "pluginMain" to project.property("pluginMain"),
            "version" to project.version
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set(project.property("pluginName").toString())
        archiveVersion.set("") // keep the file name stable for plugin updates
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from({
            configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
        })
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/versions/**/module-info.class", "module-info.class"
        )
    }

    build {
        dependsOn(jar)
    }
}
