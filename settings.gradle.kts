import java.util.Properties

// The Light SDK is consumed as source via the `light-sdk/` git submodule
// (pinned to a specific upstream commit). Neither :sdk:client nor the
// `com.thelightphone.light-sdk` Gradle plugin are published as artifacts, so
// we graft the submodule's projects into this build as real projects and
// pull in its Gradle plugin via an included build. To bump the SDK:
//   git -C light-sdk fetch && git -C light-sdk checkout <ref>
//   git add light-sdk && git commit
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
// Opt-in to also graft the SDK's LightOS emulator app (for running the tool on
// a desktop Android emulator). Off by default so Light's tool-only build/review
// pipeline and the release workflow never configure it. Enable with any of:
//   ./gradlew -PwithEmulator ...        (per-invocation; bare flag is enough)
//   withEmulator=true   in local.properties   (persistent, local)
//   LIGHT_WITH_EMULATOR=true in the environment
// A gradle property counts as enabled unless it is explicitly "false", so a
// bare `-PwithEmulator` (which Gradle sets to "") turns it on.
val emulatorProp = startParameter.projectProperties["withEmulator"]
val withEmulator =
    (emulatorProp != null && !emulatorProp.equals("false", ignoreCase = true)) ||
        localProperties.getProperty("withEmulator")?.toBoolean() == true ||
        System.getenv("LIGHT_WITH_EMULATOR")?.toBoolean() == true

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The Light keyboard (a transitive dep of :sdk:ui) is served from JitPack,
        // matching the pinned SDK's own settings.gradle.kts.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    // Reuse the SDK's own version catalog so this shell never drifts from
    // whatever the pinned submodule expects.
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "somafm-app"

// The `light.sdk` Gradle plugin lives in the submodule and is contributed as
// an included build (matches the SDK monorepo's own `includeBuild("plugin")`).
includeBuild("light-sdk/plugin")

// SDK library projects, sourced from the submodule. :sdk:client transitively
// needs :sdk:ui, :sdk:shared and :lint-rules; :sdk:server / :sdk:emulator are
// LightOS-side and intentionally omitted.
include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")
// :sdk is just a container project (no build script); point it at the
// submodule so Gradle doesn't look for a ./sdk dir at this repo's root.
include(":sdk")
project(":sdk").projectDir = file("light-sdk/sdk")
include(":sdk:shared")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")
include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")
include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

// The tool itself — this is the only dev-owned module, and the only thing
// Light's build/review pipeline extracts (tool/build.gradle.kts,
// tool/lighttool.toml, tool/src/main/**).
include(":tool")

// Dev-only: the LightOS emulator app and its server, grafted from the same
// submodule when `withEmulator` is set. Lets you install the emulator + tool on
// a desktop Android emulator and exercise the tool end-to-end. :sdk:emulator
// needs :sdk:server (which only needs :sdk:shared, already included above).
if (withEmulator) {
    include(":sdk:server")
    project(":sdk:server").projectDir = file("light-sdk/sdk/server")
    include(":sdk:emulator")
    project(":sdk:emulator").projectDir = file("light-sdk/sdk/emulator")
}
