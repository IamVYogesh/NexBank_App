import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase is optional: the app runs on a fake auth backend until you drop in google-services.json.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val googleWebClientId: String = localProps.getProperty("GOOGLE_WEB_CLIENT_ID", "")
val useFakeBackend: String = (project.findProperty("useFakeBackend") as String?) ?: "true"

android {
    namespace = "com.nexbank.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexbank.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_URL", "\"https://api.nexbank.example/\"")
        buildConfigField("boolean", "USE_FAKE_BACKEND", useFakeBackend)
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    // One codebase, several markets (see core/config/CountryConfig.kt for runtime switching).
    flavorDimensions += "market"
    productFlavors {
        create("uk") {
            dimension = "market"
            buildConfigField("String", "DEFAULT_COUNTRY", "\"UK\"")
            resValue("string", "app_name", "NexBank UK")
        }
        create("mx") {
            dimension = "market"
            buildConfigField("String", "DEFAULT_COUNTRY", "\"MEXICO\"")
            resValue("string", "app_name", "NexBank MX")
        }
        create("india") {
            dimension = "market"
            buildConfigField("String", "DEFAULT_COUNTRY", "\"INDIA\"")
            resValue("string", "app_name", "NexBank IN")
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
        dataBinding = true // used by the legacy XML Help Center module
    }

    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":design-library"))

    // Core / UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation (Compose + XML Navigation Component)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Async
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)

    // DI: Hilt (on top of Dagger 2) + plain Dagger 2 component (see di/dagger2)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Persistence + security
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)

    // Paging, background work
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime)

    // Firebase + Google sign-in (Credential Manager)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)

    implementation(libs.timber)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
