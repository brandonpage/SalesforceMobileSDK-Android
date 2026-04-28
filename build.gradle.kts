apply(plugin = "io.github.gradle-nexus.publish-plugin")
apply(from = "${rootDir}/publish/publish-root.gradle")

buildscript {
    repositories {
        maven("https://plugins.gradle.org/m2/")
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.12.0")
        classpath("io.github.gradle-nexus:publish-plugin:2.0.0")
        // Vector DB spike Phase 4: bumped from 1.9.24 \u2192 2.2.21 because the
        // Google AI Edge LiteRT-LM library used by the RAG sample
        // (`com.google.ai.edge.litertlm:litertlm-android`) ships classes
        // with Kotlin metadata version 2.3.0, which only a 2.2.x or
        // newer compiler can read. K2 (the default in 2.x) is
        // source-compatible with the SDK; the only knock-on changes are
        // the api/languageVersion bump below and the `-opt-in=` syntax.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        // Vector DB spike Phase 4: Kotlin 2.0+ replaced `composeOptions {
        // kotlinCompilerExtensionVersion }` with this dedicated plugin.
        // Adding it here lets modules apply it as `kotlin("plugin.compose")`
        // without an inline version pin.
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
        classpath("org.jacoco:org.jacoco.core:0.8.13")
    }
}

allprojects {
    group = "com.salesforce.mobilesdk"
    version = "14.0.0"

    // Vector DB spike Phase 4: project bumped from Kotlin 1.9.24 \u2192 2.2.21 to support the
    // LiteRT-LM library (its classes carry metadata version 2.3.0). Setting languageVersion
    // also constrains the metadata reader level, so we let it default to the compiler version
    // (2.2). apiVersion stays unset for the same reason \u2014 the compiled SDK output metadata
    // matches the compiler. Consumer SDK Kotlin floor moves from 1.6 \u2192 2.2 as a result.
    //
    // Note: the legacy `kotlinOptions { }` block is a hard error under KGP 2.2; migrated to
    // the `compilerOptions { }` DSL.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            // K2 renamed `-Xopt-in` to `-opt-in`; the old form is now an error.
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }
}
