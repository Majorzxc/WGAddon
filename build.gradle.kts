plugins {
    java
}

group = "me.majorzxc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // Минимальные поддерживаемые версии: Paper 1.21.4 и WorldGuard 7.0.13.
    // Используется только стабильное API, поэтому плагин работает и на более новых версиях.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.13")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // SnakeYAML поставляется вместе с Paper; в тестах подключаем его явно.
    testImplementation("org.yaml:snakeyaml:2.2")
    // Настоящий WorldGuard (без сервера) — для сценарного теста синхронизации
    testImplementation("com.sk89q.worldguard:worldguard-core:7.0.13")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Java 21 — минимальная версия для Minecraft 1.21.x; собираем любым JDK 21+
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("WGRegionList-${project.version}.jar")
}
