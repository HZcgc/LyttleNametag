plugins {
    java
}

group = "com.lyttledev"
version = providers.gradleProperty("pluginVersion").get()
description = "LyttleNametag"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
    compileOnly("com.github.retrooper:packetevents-spigot:${providers.gradleProperty("packetEventsVersion").get()}")
    compileOnly("net.luckperms:api:${providers.gradleProperty("luckPermsVersion").get()}")
    compileOnly("me.clip:placeholderapi:${providers.gradleProperty("placeholderApiVersion").get()}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val resourceProperties = mapOf("version" to project.version.toString())
    inputs.properties(resourceProperties)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(resourceProperties)
    }
}

tasks.jar {
    archiveBaseName.set("LyttleNametag")
}
