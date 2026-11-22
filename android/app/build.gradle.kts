import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is configured entirely from the environment so that no key or
// password can ever be committed. CI supplies these from GitHub Actions
// secrets; a local build without them simply produces an unsigned release
// (which cannot be installed over another signature).
val releaseKeystorePath: String? = System.getenv("INSTANT_MIC_KEYSTORE")
val releaseKeystorePassword: String? = System.getenv("INSTANT_MIC_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("INSTANT_MIC_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("INSTANT_MIC_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).exists()

/** Version code/name come from the git tag in CI, so every release is unique. */
val releaseVersionName: String =
    System.getenv("INSTANT_MIC_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val releaseVersionCode: Int =
    System.getenv("INSTANT_MIC_VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "com.tonbiattack.instantmic"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.tonbiattack.instantmic"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Minification stays off: the app is small, and keeping the stack
            // traces readable matters more than a few hundred KB here.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Several libraries ship licence/notice files under the same names,
            // which would otherwise collide. They are NOT discarded: the full
            // attributions live in THIRD_PARTY_NOTICES.md, which is bundled as an
            // asset below, so Apache-2.0 section 4(d) is satisfied.
            excludes += "/META-INF/CONTRIBUTORS.md"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/*.kotlin_module"
        }
    }

    sourceSets["main"].assets.srcDir("$rootDir/../licenses")
    // The help document is a plain asset, so a JVM test can read it from the
    // test classpath and fail when an edit breaks its structure.
    sourceSets["test"].resources.srcDir("src/main/assets")

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Compose UI tests run on the JVM through Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Kanji-to-kana conversion for the optional "write everything in hiragana"
    // mode. The analyser runs on the phone; audio never leaves it.
    implementation(libs.kuromoji)
    // Google Code Scanner: a system-provided, fast scanner that needs no camera
    // permission. ZXing stays as a fallback for devices without Play services.
    implementation(libs.play.services.code.scanner)
    // Code Scanner pulls in an ancient Fragment via Play services; Activity Result
    // needs at least 1.3.0.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests; the mockable android.jar only has stubs.
    testImplementation(libs.json)
    // Compose UI tests on the JVM via Robolectric, so CI covers the screens
    // without a device. The manifest artifact supplies the activity the test
    // rule needs.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
