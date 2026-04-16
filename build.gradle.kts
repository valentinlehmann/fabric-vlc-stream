plugins {
    `maven-publish`
}

val isUnobfuscated = property("mc.unobfuscated").toString().toBoolean()
val javaRelease = property("mc.java").toString().toInt()

// Conditionally apply the correct Loom plugin.
// 26.1+ is unobfuscated -> use fabric-loom (no remapping).
// Older versions need fabric-loom-remap.
if (isUnobfuscated) {
    apply(plugin = "net.fabricmc.fabric-loom")
} else {
    apply(plugin = "net.fabricmc.fabric-loom-remap")
}

version = "${property("mod.version")}+${stonecutter.current.version}"
base.archivesName = property("mod.id") as String

repositories {
    mavenCentral()
}

// Loom extension is applied dynamically, access via extensions.
val loom = extensions.getByName<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom")

loom.apply {
    splitEnvironmentSourceSets()
    mods.register("vlc-stream") {
        sourceSet(sourceSets["main"])
        sourceSet(sourceSets["client"])
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:${stonecutter.current.version}")

    // Obfuscated versions need Mojang mappings; 26.1+ is unobfuscated.
    if (!isUnobfuscated) {
        "mappings"(loom.officialMojangMappings())
    }

    // 26.1+ uses plain implementation (no remapping); older uses modImplementation.
    val modDep = if (isUnobfuscated) "implementation" else "modImplementation"
    modDep("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modDep("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // VLCJ — version-independent, always bundled.
    "implementation"("uk.co.caprica:vlcj:${property("deps.vlcj")}")
    "include"("uk.co.caprica:vlcj:${property("deps.vlcj")}")
    "implementation"("uk.co.caprica:vlcj-natives:${property("deps.vlcj")}")
    "include"("uk.co.caprica:vlcj-natives:${property("deps.vlcj")}")
    "implementation"("net.java.dev.jna:jna:${property("deps.jna")}")
    "include"("net.java.dev.jna:jna:${property("deps.jna")}")
    "implementation"("net.java.dev.jna:jna-platform:${property("deps.jna")}")
    "include"("net.java.dev.jna:jna-platform:${property("deps.jna")}")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "mc_dep" to (findProperty("mod.mc_dep") ?: ""),
        "java_version" to javaRelease.toString()
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
}

java {
    withSourcesJar()
    val javaVersion = JavaVersion.toVersion(javaRelease)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.name}" }
    }
}

// Collect all version JARs into a single output folder.
// Unobfuscated Minecraft (26.1+) uses fabric-loom without remapping, so the
// distributable jar is produced by `jar` rather than `remapJar`. Both task
// types extend AbstractArchiveTask which exposes `archiveFile`.
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    val modVersion = project.property("mod.version") as String
    val jarTaskName = if (isUnobfuscated) "jar" else "remapJar"
    val jarTask = tasks.named(jarTaskName, org.gradle.api.tasks.bundling.AbstractArchiveTask::class)
    from(jarTask.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    dependsOn("build")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
