include("libs:SalesforceAnalytics")
include("libs:SalesforceSDK")
include("libs:SmartStore")
include("libs:MobileSync")
include("libs:SalesforceHybrid")
include("libs:SalesforceReact")
include("hybrid:HybridSampleApps:AccountEditor")
include("native:NativeSampleApps:AppConfigurator")
include("native:NativeSampleApps:ConfiguredApp")
include("hybrid:HybridSampleApps:MobileSyncExplorerHybrid")
include("native:NativeSampleApps:RestExplorer")
include("native:NativeSampleApps:AuthFlowTester")

// Vector DB spike: consume a locally-vendored sqlcipher-android build that
// statically links sqlite-vec + LibTomCrypt (see VectorDBImplementationPlan.md
// Phase 1). `net.zetetic:sqlcipher-android` in any dependency block below is
// transparently substituted with the `:sqlcipher` project inside the included
// build.
includeBuild("external/sqlcipher-android") {
    dependencySubstitution {
        substitute(module("net.zetetic:sqlcipher-android"))
            .using(project(":sqlcipher"))
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        // Android JSC is installed from NPM.
        maven("${rootProject.projectDir}/libs/SalesforceReact/node_modules/jsc-android/dist") // For stand-alone MSDK builds.
        maven("${rootProject.projectDir}/../../node_modules/jsc-android/dist") // For template app builds.
        google()
        mavenCentral()
    }
}