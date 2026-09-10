import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningProperty(name: String): String =
    requireNotNull(releaseKeystoreProperties.getProperty(name)) {
        "Missing '$name' in ${releaseKeystorePropertiesFile.name}"
    }

android {
    namespace = "tr.com.uslanozan.evritext"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.com.uslanozan.evritext"
        // 28 costs nothing and covers older boxes; the target device reports 30.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor calls URLDecoder.decode(String, Charset), which Android
        // only gained in API 33; the target box is API 30. NewPipe's own app solves
        // it the same way.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            // android.util.Log is a stub in unit tests; without this every call to it
            // throws instead of returning 0, which would fail tests for no reason.
            isReturnDefaultValues = true

            all {
                // Full-film cue preview is intentionally opt-in because it calls a
                // real translation API. Run with -PsegmentationPreview=true.
                it.systemProperty(
                    "evritext.segmentationPreview",
                    providers.gradleProperty("segmentationPreview").orNull ?: "false",
                )
                // Generates Turkish SRT files from a local Tears of Steel copy for
                // side-by-side playback in VLC. Also uses the real Gemini API.
                it.systemProperty(
                    "evritext.vlcPreview",
                    providers.gradleProperty("vlcPreview").orNull ?: "false",
                )
                it.systemProperty(
                    "evritext.vlcMovie",
                    providers.gradleProperty("vlcMovie").orNull ?: "tears-of-steel",
                )
                it.systemProperty(
                    "evritext.vlcVideoId",
                    providers.gradleProperty("vlcVideoId").orNull ?: "",
                )
                it.systemProperty(
                    "evritext.vlcReplay",
                    providers.gradleProperty("vlcReplay").orNull ?: "false",
                )
                // These tests print real translations and track listings; the output
                // is the point, not just the pass/fail.
                it.testLogging {
                    showStandardStreams = true
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
