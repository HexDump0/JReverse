plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.hexdump0.jreverse"
version = "0.1.0"

val jadxVersion = "1.5.6"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    google()
}

// Small Java classes compiled into fixture.jar for tests; never shipped.
val fixtures = sourceSets.create("fixtures")

// The example app the start screen offers to first-time users; shipped.
val example = sourceSets.create("example")

dependencies {
    implementation("io.github.skylot:jadx-core:$jadxVersion")
    implementation("io.github.skylot:jadx-dex-input:$jadxVersion")
    implementation("io.github.skylot:jadx-java-input:$jadxVersion")
    implementation("com.google.code.gson:gson:2.14.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
    compileOnly("org.jetbrains:annotations:26.1.0") // jadx's API uses them

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.android.tools.smali:smali:3.0.10")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-serial")
}

val fixtureJar = tasks.register<Jar>("fixtureJar") {
    archiveFileName = "fixture.jar"
    destinationDirectory = layout.buildDirectory.dir("fixtures")
    from(fixtures.output)
}

// Built like a release binary: no debug info, so it decompiles the way real apps do.
tasks.named<JavaCompile>(example.compileJavaTaskName) {
    options.release = 17
    options.compilerArgs.add("-g:none")
}

tasks.jar {
    manifest.attributes("Main-Class" to "io.github.hexdump0.jreverse.engine.Main")
}

tasks.shadowJar {
    archiveClassifier = "all"
    // jadx discovers its input plugins through ServiceLoader; without merging,
    // only one jar's META-INF/services survives and DEX input silently vanishes.
    // Shadow 9 drops duplicate paths before transformers run unless told otherwise.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest.attributes(
        "Main-Class" to "io.github.hexdump0.jreverse.engine.Main",
        "Implementation-Version" to project.version,
    )
}

// ---- Bundled runtime --------------------------------------------------------

val distDir = rootProject.layout.projectDirectory.dir("../src-tauri/engine-dist")

val toolchainHome = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}.map { it.metadata.installationPath.asFile }

// Modules jdeps can't see because they are only reached reflectively.
val extraModules = listOf("jdk.zipfs", "jdk.charsets")

// Modules jdeps reports that the engine never touches at runtime:
// java.desktop only backs jadx's GUI plugin API, CFG dot export and 9-patch
// decoding (resources are skipped); java.sql is optional in Gson. Dropping
// them saves ~20 MB. Put one back if the smoke test says otherwise.
val excludedModules = listOf("java.desktop", "java.sql")

val runtimeImage = tasks.register("runtimeImage") {
    description = "Builds a trimmed Java runtime with jlink."
    dependsOn(tasks.shadowJar)
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val out = distDir.dir("runtime")
    inputs.file(jar)
    inputs.property("extraModules", extraModules)
    inputs.property("excludedModules", excludedModules)
    outputs.dir(out)
    doLast {
        val home = toolchainHome.get()
        val bin = { tool: String -> home.resolve("bin/$tool").absolutePath }
        // --list-deps, not --print-module-deps: the latter folds modules into
        // whatever requires them transitively, so excluding java.desktop
        // would silently drop java.xml with it.
        val jdeps = providers.exec {
            commandLine(
                bin("jdeps"), "--ignore-missing-deps", "--list-deps",
                "--multi-release", "21", jar.get().asFile.absolutePath,
            )
        }.standardOutput.asText.get()
        val modules = (jdeps.lines().map { it.trim().substringBefore('/') } + extraModules)
            .filter { it.matches(Regex("(java|jdk)\\.[a-z.]+")) && it !in excludedModules }
            .distinct().sorted()
        logger.lifecycle("jlink modules: ${modules.joinToString(",")}")
        val outDir = out.asFile
        outDir.deleteRecursively()
        providers.exec {
            commandLine(
                bin("jlink"), "--add-modules", modules.joinToString(","),
                "--strip-debug", "--no-man-pages", "--no-header-files",
                "--compress=zip-9", "--output", outDir.absolutePath,
            )
        }.result.get().assertNormalExitValue()
        // jlink copies the JDK's legal notices read-only, which breaks the next
        // overwrite by tauri-build's resource copy.
        outDir.walk().forEach { it.setWritable(true, true) }
    }
}

val engineJar = tasks.register("engineJar") {
    description = "Copies the fat jar to src-tauri/engine-dist/engine.jar."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val out = distDir.file("engine.jar")
    inputs.file(jar)
    outputs.file(out)
    doLast { jar.get().asFile.copyTo(out.asFile, overwrite = true) }
}

val exampleJar = tasks.register<Jar>("exampleJar") {
    description = "Builds the bundled example app into src-tauri/engine-dist/vault-example.jar."
    archiveFileName = "vault-example.jar"
    destinationDirectory = distDir
    from(example.output)
    manifest.attributes("Main-Class" to "com.example.vault.Main")
}

val dist = tasks.register("dist") {
    description = "Assembles engine.jar, the jlink runtime and the example app into src-tauri/engine-dist."
    group = "distribution"
    dependsOn(engineJar, runtimeImage, exampleJar)
}

// ---- Tests ------------------------------------------------------------------

tasks.test {
    useJUnitPlatform { excludeTags("smoke") }
    dependsOn(fixtureJar)
    systemProperty("fixture.jar", fixtureJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("fixture.smali", file("src/test/fixtures/smali").absolutePath)
}

tasks.register<Test>("smokeTest") {
    description = "Runs the real fat jar on the jlink runtime."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("smoke") }
    dependsOn(dist, fixtureJar)
    inputs.dir(distDir) // rerun whenever the runtime or jar changes
    systemProperty("fixture.jar", fixtureJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("fixture.smali", file("src/test/fixtures/smali").absolutePath)
    systemProperty("dist.dir", distDir.asFile.absolutePath)
}
