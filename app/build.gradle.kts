import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.github.triplet.play")
    alias(libs.plugins.spotless)
}

fun getProps(propName: String): String {
    val propsInEnv = System.getenv("LOCAL_PROPERTIES")
    if (propsInEnv != null) {
        val props = Properties()
        props.load(ByteArrayInputStream(Base64.getDecoder().decode(propsInEnv)))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

fun getVersionProps(propName: String): String {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

android {
    namespace = "io.nekohasekai.sfa"
    compileSdk = 36

    ndkVersion = "28.0.13004108"

    System.getenv("ANDROID_NDK_HOME")?.let { ndkPath = it }

    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "${projectDir}/schemas")
    }

    defaultConfig {
        // Deliberately NOT upstream's io.nekohasekai.sfa: that id belongs to the official
        // sing-box-for-android on Google Play. Shipping our own signature under the same id
        // makes scanners flag every build as a repackaged/tampered copy of that app
        // (Android.Riskware.Repack). A distinct id removes that signal. `namespace` stays
        // io.nekohasekai.sfa — it is only the code package (R class, AIDL, BuildConfig) and
        // has no bearing on app identity or detection.
        applicationId = "app.tarnvpn"
        minSdk = 21
        targetSdk = 35
        versionCode = getVersionProps("VERSION_CODE").toInt()
        versionName = getVersionProps("VERSION_NAME")
        base.archivesName.set("TarnVPN-${versionName}")
    }

    signingConfigs {
        create("release") {
            // NOT upstream's release.keystore: that file ships in the public
            // SagerNet/sing-box-for-android repository, so anyone can sign with it and any
            // build they produce would be accepted by Android as an update to this app.
            // tarn-release.keystore is generated locally and gitignored; if it is ever lost,
            // installed copies can no longer be updated — back it up together with the
            // KEYSTORE_PASS/ALIAS_* entries in local.properties.
            storeFile = file("tarn-release.keystore")
            storePassword = getProps("KEYSTORE_PASS")
            keyAlias = getProps("ALIAS_NAME")
            keyPassword = getProps("ALIAS_PASS")
            // v1+v2 alone verified, but only v3 carries a rotation proof — without it the key
            // above can never be replaced without every user reinstalling by hand.
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    flavorDimensions += "vendor"
    productFlavors {
        create("play") {
            minSdk = 23
        }
        create("other") {
            minSdk = 23
        }
        create("otherLegacy") {
            minSdk = 21
        }
    }

    sourceSets {
        getByName("play") {
            java.directories.add("src/minApi23/java")
            kotlin.directories.add("src/minApi23/java")
            aidl.directories.add("src/minApi23/aidl")
        }
        getByName("other") {
            java.directories.addAll(listOf("src/minApi23/java", "src/github/java"))
            kotlin.directories.addAll(listOf("src/minApi23/java", "src/github/java"))
            aidl.directories.add("src/minApi23/aidl")
        }
        getByName("otherLegacy") {
            java.directories.addAll(listOf("src/minApi21/java", "src/github/java"))
            kotlin.directories.addAll(listOf("src/minApi21/java", "src/github/java"))
            aidl.directories.add("src/minApi23/aidl")
        }
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric supplies real implementations of the android.* classes the parsing
            // code leans on (Uri, Base64, org.json), so the import logic can be tested on the
            // JVM instead of only on a device.
            isIncludeAndroidResources = true
            // Anything NOT covered by Robolectric would otherwise throw "not mocked"; returning
            // defaults keeps an unrelated stray call from failing a test about parsing.
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        fatal += "NewApi"
    }

}

androidComponents {
    onVariants { variant ->
        val variantTaskSuffix = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val copyTask = tasks.register<Sync>("copy${variantTaskSuffix}TarnApks") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { fileName ->
                    fileName
                        .replace("-release", "")
                        .replace("-otherLegacy", "-legacy-android-5")
                        .replace("-other", "")
                }
            }
            into(layout.buildDirectory.dir("outputs/tarn-apk/${variant.name}"))
        }
        tasks.matching { it.name == "assemble$variantTaskSuffix" }.configureEach {
            finalizedBy(copyTask)
        }
    }
}

dependencies {
    // libbox
    "playImplementation"(files("libs/libbox.aar"))
    "otherImplementation"(files("libs/libbox.aar"))
    "otherLegacyImplementation"(files("libs/libbox-legacy.aar"))

    // API level specific versions
    val lifecycleVersion23 = "2.10.0"
    val roomVersion23 = "2.8.4"
    val workVersion23 = "2.11.1"
    val cameraVersion23 = "1.5.3"
    val browserVersion23 = "1.9.0"

    val lifecycleVersion21 = "2.9.4"
    val roomVersion21 = "2.7.2"
    val workVersion21 = "2.10.5"
    val cameraVersion21 = "1.4.2"
    val browserVersion21 = "1.9.0"

    // Common dependencies (no API level difference)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.7")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.blacksquircle.ui:editorkit:2.2.0")
    implementation("com.blacksquircle.ui:language-json:2.2.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.5.0-android")

    // API 23+ dependencies (play/other)
    "playImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion23")
    "playImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion23")
    "playImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion23")
    "playImplementation"("androidx.room:room-runtime:$roomVersion23")
    "playImplementation"("androidx.work:work-runtime-ktx:$workVersion23")
    "playImplementation"("androidx.camera:camera-view:$cameraVersion23")
    "playImplementation"("androidx.camera:camera-lifecycle:$cameraVersion23")
    "playImplementation"("androidx.camera:camera-camera2:$cameraVersion23")
    "playImplementation"("androidx.browser:browser:$browserVersion23")
    "playAnnotationProcessor"("androidx.room:room-compiler:$roomVersion23")
    "kspPlay"("androidx.room:room-compiler:$roomVersion23")

    "otherImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion23")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion23")
    "otherImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion23")
    "otherImplementation"("androidx.room:room-runtime:$roomVersion23")
    "otherImplementation"("androidx.work:work-runtime-ktx:$workVersion23")
    "otherImplementation"("androidx.camera:camera-view:$cameraVersion23")
    "otherImplementation"("androidx.camera:camera-lifecycle:$cameraVersion23")
    "otherImplementation"("androidx.camera:camera-camera2:$cameraVersion23")
    "otherImplementation"("androidx.browser:browser:$browserVersion23")
    "kspOther"("androidx.room:room-compiler:$roomVersion23")

    // API 21 dependencies (otherLegacy)
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion21")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion21")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion21")
    "otherLegacyImplementation"("androidx.room:room-runtime:$roomVersion21")
    "otherLegacyImplementation"("androidx.work:work-runtime-ktx:$workVersion21")
    "otherLegacyImplementation"("androidx.camera:camera-view:$cameraVersion21")
    "otherLegacyImplementation"("androidx.camera:camera-lifecycle:$cameraVersion21")
    "otherLegacyImplementation"("androidx.camera:camera-camera2:$cameraVersion21")
    "otherLegacyImplementation"("androidx.browser:browser:$browserVersion21")
    "kspOtherLegacy"("androidx.room:room-compiler:$roomVersion21")

    // Play Store specific
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")
    "playImplementation"("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // Shizuku (play and other flavors, API 23+ only)
    val shizukuVersion = "12.2.0"
    "playImplementation"("dev.rikka.shizuku:api:$shizukuVersion")
    "playImplementation"("dev.rikka.shizuku:provider:$shizukuVersion")
    "otherImplementation"("dev.rikka.shizuku:api:$shizukuVersion")
    "otherImplementation"("dev.rikka.shizuku:provider:$shizukuVersion")

    // libsu for ROOT package query (all flavors)
    val libsuVersion = "6.0.0"
    "playImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "playImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")
    "otherImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "otherImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")
    "otherLegacyImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "otherLegacyImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")

    // Compose dependencies - API 23+ (play/other)
    val composeBom23 = platform("androidx.compose:compose-bom:2026.02.00")
    val activityVersion23 = "1.12.4"
    val lifecycleComposeVersion23 = "2.10.0"

    "playImplementation"(composeBom23)
    "playImplementation"("androidx.compose.material3:material3")
    "playImplementation"("androidx.compose.material3.adaptive:adaptive")
    "playImplementation"("androidx.compose.ui:ui")
    "playImplementation"("androidx.compose.ui:ui-tooling-preview")
    "playImplementation"("androidx.compose.material:material-icons-extended")
    "playImplementation"("androidx.activity:activity-compose:$activityVersion23")
    "playImplementation"("androidx.navigation:navigation-compose:2.9.7")
    "playImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion23")
    "playImplementation"("androidx.compose.runtime:runtime-livedata")

    "otherImplementation"(composeBom23)
    "otherImplementation"("androidx.compose.material3:material3")
    "otherImplementation"("androidx.compose.material3.adaptive:adaptive")
    "otherImplementation"("androidx.compose.ui:ui")
    "otherImplementation"("androidx.compose.ui:ui-tooling-preview")
    "otherImplementation"("androidx.compose.material:material-icons-extended")
    "otherImplementation"("androidx.activity:activity-compose:$activityVersion23")
    "otherImplementation"("androidx.navigation:navigation-compose:2.9.7")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion23")
    "otherImplementation"("androidx.compose.runtime:runtime-livedata")

    // Compose dependencies - API 21 (otherLegacy)
    val composeBom21 = platform("androidx.compose:compose-bom:2025.01.00")
    val activityVersion21 = "1.11.0"
    val lifecycleComposeVersion21 = "2.9.4"

    "otherLegacyImplementation"(composeBom21)
    "otherLegacyImplementation"("androidx.compose.material3:material3")
    "otherLegacyImplementation"("androidx.compose.material3.adaptive:adaptive")
    "otherLegacyImplementation"("androidx.compose.ui:ui")
    "otherLegacyImplementation"("androidx.compose.ui:ui-tooling-preview")
    "otherLegacyImplementation"("androidx.compose.material:material-icons-extended")
    "otherLegacyImplementation"("androidx.activity:activity-compose:$activityVersion21")
    "otherLegacyImplementation"("androidx.navigation:navigation-compose:2.9.7")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion21")
    "otherLegacyImplementation"("androidx.compose.runtime:runtime-livedata")

    // JVM unit tests. Robolectric is what makes the import path testable without a device:
    // ProxyUriParser is built on android.net.Uri / android.util.Base64 / org.json, none of
    // which exist in a plain JVM test.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")

    // Debug/Test dependencies
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "androidTestPlayImplementation"(composeBom23)
    "androidTestOtherImplementation"(composeBom23)
    "androidTestOtherLegacyImplementation"(composeBom21)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Common Compose-related libraries
    implementation("sh.calvin.reorderable:reorderable:3.0.0")
    implementation("com.github.jeziellago:compose-markdown:0.5.8")
    implementation("org.kodein.emoji:emoji-kt:2.3.0")

    // Terminal emulator
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Xposed API for self-hooking VPN hide module
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly(project(":libxposed-api"))
}

val playCredentialsJSON = rootProject.file("service-account-credentials.json")
if (playCredentialsJSON.exists()) {
    play {
        serviceAccountCredentials.set(playCredentialsJSON)
        defaultToAppBundles.set(true)
        val version = getVersionProps("VERSION_NAME")
        track.set(
            if (version.contains("alpha") || version.contains("beta")/* || version.contains("rc")*/) {
                "beta"
            } else {
                "production"
            }
        )
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ))
    }
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}
