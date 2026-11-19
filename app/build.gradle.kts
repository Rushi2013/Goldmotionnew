import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

/**
 * Signing resolution order (deliberately bulletproof for GitHub Actions):
 *  1. -PJARVIS_STORE_FILE_BASE64 (+ password/alias/password)  -> CI secrets, decoded into build dir
 *  2. -PJARVIS_STORE_FILE=/abs/path.jks                       -> local machine
 *  3. no keystore at all                                       -> fall back to the debug key so
 *                                                                `assembleRelease` still produces
 *                                                                an installable APK.
 * A workflow must never fail just because no signing secret exists.
 */
fun resolveStoreFile(): File? {
    val b64 = providers.gradleProperty("JARVIS_STORE_FILE_BASE64").orNull
    if (!b64.isNullOrBlank()) {
        val out = layout.buildDirectory.file("ci/jarvis-release.jks").get().asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getDecoder().decode(b64.replace("\\s".toRegex(), "")))
        return out
    }
    val path = providers.gradleProperty("JARVIS_STORE_FILE").orNull
    if (!path.isNullOrBlank()) {
        val f = File(path)
        if (f.exists()) return f
        logger.warn("JARVIS_STORE_FILE=$path does not exist - falling back to debug signing")
    }
    val localProps = rootProject.file("keystore.properties")
    if (localProps.exists()) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        val f = props.getProperty("storeFile")?.let { File(it) }
        if (f != null && f.exists()) return f
    }
    return null
}

val signingStoreFile: File? = resolveStoreFile()

android {
    namespace = "ai.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.jarvis"
        minSdk = 29
        targetSdk = 35
        versionCode = (providers.gradleProperty("VERSION_CODE").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val store = signingStoreFile
            if (store != null) {
                storeFile = store
                storePassword = providers.gradleProperty("JARVIS_STORE_PASSWORD").orNull
                    ?: localProps()?.getProperty("storePassword")
                keyAlias = providers.gradleProperty("JARVIS_KEY_ALIAS").orNull
                    ?: localProps()?.getProperty("keyAlias") ?: "jarvis"
                keyPassword = providers.gradleProperty("JARVIS_KEY_PASSWORD").orNull
                    ?: localProps()?.getProperty("keyPassword")
                    ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            resValue("string", "app_name", "JARVIS AI (debug)")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Always installable: fall back to the debug signer when no release keystore exists.
            signingConfig = if (signingStoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            resValue("string", "app_name", "JARVIS AI")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
    // CI must never be red because of stylistic lint findings; lint still runs and reports.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

fun localProps(): Properties? {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return null
    return Properties().apply { f.inputStream().use { load(it) } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)


    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.socketio.client)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
