plugins {
    android
    `kotlin-android`
}

dependencies {
    implementation(project(":libs:SalesforceSDK"))
    // Vector DB spike Phase 4: SmartStore is needed for the RAG demo
    // (SmartStoreVectorSearch.vectorSearch + soup CRUD).
    implementation(project(":libs:SmartStore"))
    implementation("androidx.core:core-ktx:1.16.0") // Update requires API 36 compileSdk
    implementation("androidx.tracing:tracing:1.3.0")
    implementation("com.google.android.material:material:1.13.0")
    androidTestImplementation("androidx.test:runner:1.5.1") {
        exclude("com.android.support", "support-annotations")
    }

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.appcompat:appcompat-resources:1.7.1")

    // Vector DB spike Phase 4: MediaPipe TextEmbedder is still the right
    // wrapper for the Universal Sentence Encoder tflite (small,
    // well-supported, on Maven Central).
    implementation("com.google.mediapipe:tasks-text:0.10.14")

    // For the generator side we use Google AI Edge's LiteRT-LM library
    // instead of mediapipe `tasks-genai`. The MediaPipe LLM Inference API
    // is being deprecated in favour of LiteRT-LM, and the new `.litertlm`
    // bundle format that Gemma 3 / 3n / 4 ship in is *only* loadable
    // through this library. Lives on Google's Maven (the project already
    // declares `google()` in `settings.gradle.kts`).
    //
    // Pinned to 0.10.2 (latest at time of writing). The library is built
    // against Kotlin 2.2; we pin its transitive `kotlin-stdlib` down to
    // 2.0.21 below so the project's 1.9.24 compiler can still read it
    // (the 1.9.x compiler accepts metadata up to 2.0).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")

    androidTestImplementation("androidx.test:rules:1.5.0") {
        exclude("com.android.support", "support-annotations")
    }
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0") {
        exclude("com.android.support", "support-annotations")
    }
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

android {
    namespace = "com.salesforce.samples.restexplorer"
    testNamespace = "com.salesforce.samples.restexplorer.tests"

    compileSdk = 36

    defaultConfig {
        targetSdk = 36
        minSdk = 28
    }

    buildTypes {
        debug {
            enableAndroidTestCoverage = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            resources.srcDirs("src")
            aidl.srcDirs("src")
            renderscript.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs("assets")
        }

        getByName("androidTest") {
            setRoot("../test/RestExplorerTest")
            java.srcDirs("../test/RestExplorerTest/src")
            resources.srcDirs("../test/RestExplorerTest/src")
            res.srcDirs("../test/RestExplorerTest/res")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/DEPENDENCIES", "META-INF/NOTICE")
            pickFirsts += setOf("protobuf.meta")
        }
    }

    defaultConfig {
        testApplicationId = "com.salesforce.samples.restexplorer.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        abortOnError = false
        xmlReport = true
    }

    buildFeatures {
        renderScript = true
        aidl = true
        buildConfig = true
    }

    kotlin {
        jvmToolchain(17)
    }
}
