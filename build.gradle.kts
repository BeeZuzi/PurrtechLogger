plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    // DisplayGUI is a soft dependency (the admin GUI is optional, see paper-plugin.yml) and isn't
    // published to a repository - both plugins are local projects on this machine, so a direct
    // file reference to its build output is the pragmatic choice for a solo-dev setup.
    compileOnly(files("/Users/Zuzka/IdeaProjects/DisplayGUI/build/libs/DisplayGUI-1.0.jar"))

    // TemplateConfigLoader (YamlConfiguration/Material) and StackMath (pure logic) are testable
    // without a running server - paper-api's config/Material classes are self-contained. Anything
    // touching ItemStack/ItemMeta internals (TrackedItemTag, TemplateMatcher) isn't covered here;
    // that would need a Bukkit mock library (e.g. MockBukkit), not added in this pass.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        // sqlite-jdbc's native library has the JDBC class name ("org/sqlite/core/NativeDB")
        // baked into its JNI bindings, so relocating org.sqlite breaks native loading at runtime.
        // Paper isolates each plugin in its own classloader, so an unrelocated bundled copy
        // doesn't clash with another plugin bundling the same driver.
        archiveClassifier.set("")
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }
}
