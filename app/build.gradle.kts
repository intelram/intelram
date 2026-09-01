plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Both scoped to the new Analyst Mode module (Hilt DI + Room cache) — the rest of the app
    // keeps its existing manual-DI pattern (SettingsRepository, BluetoothChatManager.getInstance,
    // etc.) untouched. See analyst/di/AnalystModule.kt.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.threadprotection.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.threadprotection.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.4.1"

        // Real Google Sign-In requires a web/server OAuth client ID from the
        // Google Cloud Console (Credential Manager needs the *web* client ID,
        // not the Android one). Leave blank to run in demo sign-in mode.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bouncycastle and jspecify (a new transitive dependency via Dagger/Hilt) both
            // package an identical, unused OSGi manifest at this path — a routine merge
            // conflict, not a real collision (neither app code nor either library reads it
            // at runtime).
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.bouncycastle)

    // Analyst Mode only (CVE/EPSS/KEV intelligence — see analyst/). Hilt for DI, Room for the
    // local CVE watchlist cache. Reuses the existing Retrofit/OkHttp stack (network/NetworkModule)
    // rather than standing up a second HTTP client.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // JVM unit tests for the pure chat/navigation rules — see app/src/test. These run with
    // `./gradlew :app:testDebugUnitTest`, on a plain JVM, with no device or emulator involved.
    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
