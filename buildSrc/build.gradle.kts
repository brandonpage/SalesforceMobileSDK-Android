plugins { `kotlin-dsl` }

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.12.0")
    // Vector DB spike Phase 4: bumped to 2.2.21 in lock-step with the root
    // buildscript classpath. The convention plugins under
    // `buildSrc/src/main/kotlin/` apply `kotlin-android` to every module,
    // so this is the actual KGP version that ends up in the module
    // classpaths. The kotlin-dsl plugin used by buildSrc itself still ships
    // Kotlin 1.9 internally (and that's fine \u2014 it only compiles the
    // Gradle scripts under `buildSrc/src/main/kotlin/`); the stdlib
    // declared here picks the version those scripts compile against, which
    // we keep at 2.0.21 because the embedded 1.9 compiler can read up to
    // metadata 2.0 only.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
