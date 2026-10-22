import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.llama"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "ru.ivan.miniai"

        minSdk = 30
        targetSdk = 36

        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val signProps = Properties().apply {
        val f = file(System.getenv("MINI_AI_SIGNING") ?: "/workspace/keys/mini-ai/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (signProps.isNotEmpty()) {
                storeFile = file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            if (signProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation("androidx.security:security-crypto:1.1.0")

    implementation(project(":lib"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Release gate: every Kotlin `external fun` must have its Java_* symbol in libai-chat.so for every ABI.
val verifyJni = tasks.register<Exec>("verifyJniRelease") {
    val ndkNm = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    doFirst {
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }!!.first()
        commandLine("python3", rootProject.file("scripts/check_jni.py").path, apk.path, ndkNm.path,
            rootProject.file("lib/src/main/java").path)
    }
}
afterEvaluate { tasks.named("assembleRelease") { finalizedBy(verifyJni) } }
