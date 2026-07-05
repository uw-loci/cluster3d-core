plugins {
    // QuPath Gradle convention plugin (provides the version catalog + JDK toolchain)
    id("qupath-conventions")
    // Publish to Maven Local so the navigator (and later QP-CAT) can depend on it.
    id("maven-publish")
    // Auto-formatting (palantirJavaFormat) -- gates the build via `check`
    id("com.diffplug.spotless") version "7.0.2"
}

// This is the Apache-2.0 "CoreLib" (Rueden CoreLib/HostPlugin pattern): the shared
// 3D-viewer code, usable by any host frontend (the standalone GPL navigator shell,
// and later a QP-CAT tab). It copies NO QuPath GPL source; it is Mike's own code
// plus helpers adapted from Apache-2.0 QP-CAT. Apache-2.0 flows into either frontend
// without forcing GPL. Produces a NORMAL library jar (not a shaded extension jar) --
// it ships NO QuPathExtension service file, so the host never loads it as an extension.
qupathExtension {
    name = "cluster3d-core"
    group = "io.github.uw-loci"
    version = "0.1.2"
    description = "Apache-2.0 shared 3D point-cloud viewer core for Cluster 3D Navigator and host plugins."
    automaticModule = "io.github.uw.loci.cluster3d.core"
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
        }
    }
}

val javafxVersion = "17.0.2"

dependencies {
    // QuPath + JavaFX + logging are provided by the QuPath host at runtime, so they
    // are compileOnly here -- NOT published as transitive deps and NOT bundled. The
    // frontend that shades this library supplies them.
    compileOnly(libs.bundles.qupath)
    compileOnly(libs.bundles.logging)
    compileOnly(libs.qupath.fxtras)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
}

// Publish the plain library jar to Maven Local (monorepo publishToMavenLocal pattern
// like tiles-to-pyramid). The navigator resolves io.github.uw-loci:cluster3d-core:0.1.2
// from mavenLocal and shades it.
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.uw-loci"
            artifactId = "cluster3d-core"
            version = "0.1.2"
            from(components["java"])
        }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21) // QuPath 0.7 runs on Java 21; pin bytecode target
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    // Move JavaFX JARs from classpath to module path so --add-modules can find them.
    doFirst {
        val cp = classpath.files
        val fxJars = cp.filter { it.name.startsWith("javafx-") }
        if (fxJars.isNotEmpty()) {
            classpath = files(cp - fxJars)
            jvmArgs(
                "--module-path", fxJars.joinToString(File.pathSeparator),
                "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Spotless -- auto-formatting (gates the build via `check`)
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.90.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// ASCII-only enforcement (CLAUDE.md policy: no chars > 0x7F in Java sources).
// ---------------------------------------------------------------------------
tasks.register("checkAsciiOnly") {
    description = "Fails if any Java source file contains non-ASCII characters (> 0x7F)"
    group = "verification"
    val srcDirs = fileTree("src") { include("**/*.java") }
    inputs.files(srcDirs)
    doLast {
        val violations = mutableListOf<String>()
        srcDirs.forEach { file ->
            file.readText().lines().forEachIndexed { idx, line ->
                line.forEachIndexed { col, ch ->
                    if (ch.code > 0x7F) {
                        violations.add(
                            "${file.relativeTo(projectDir)}:${idx + 1}:${col + 1}  " +
                                    "'$ch' (U+${"04X".format(ch.code)})"
                        )
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-ASCII characters found (will break on Windows cp1252):\n" +
                        violations.joinToString("\n")
            )
        }
        logger.lifecycle("checkAsciiOnly: all Java sources are ASCII-clean")
    }
}
tasks.named("check") { dependsOn("checkAsciiOnly") }

// QuPath 0.7.0's maven artifacts are published as requiring JVM 25; force resolvable
// classpaths to request JVM 25 so the deps resolve. Bytecode target (21) is unaffected.
configurations.configureEach {
    if (isCanBeResolved) {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
