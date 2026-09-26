plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

fun String.asKotlinStringLiteral(): String = buildString {
    append('"')
    this@asKotlinStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

fun currentGitSha(): String {
    fun exec(vararg args: String): String = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().trim()
    } catch (_: Throwable) {
        ""
    }
    return listOf("git", "rev-parse", "HEAD").let { exec(*it.toTypedArray()) }
        .ifBlank { "" }
        .take(40)
}

android {
    namespace = "com.lladlam.melox"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lladlam.melox.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 19
        versionName = "0.6.1"
        buildConfigField(
            "String",
            "SPOTIFY_CLIENT_ID",
            providers.gradleProperty("meloxSpotifyClientId").orNull.orEmpty().asKotlinStringLiteral(),
        )
        buildConfigField(
            "String",
            "GIT_SHA",
            currentGitSha().asKotlinStringLiteral(),
        )
    }

    // Release credentials are supplied from the command line or CI secrets;
    // passwords and the external keystore are deliberately not committed.
    val keystorePath = providers.gradleProperty("meloxReleaseStoreFile").orNull
    val keystorePassword = providers.gradleProperty("meloxReleaseStorePassword").orNull
    val keyAliasValue = providers.gradleProperty("meloxReleaseKeyAlias").orNull
    val keyPasswordValue = providers.gradleProperty("meloxReleaseKeyPassword").orNull
    val meloxReleaseSigning = if (
        keystorePath != null && keystorePassword != null && keyAliasValue != null && keyPasswordValue != null
    ) {
        signingConfigs.create("meloxRelease") {
            storeFile = file(keystorePath)
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    } else null

    // AGP resolves the default debug keystore through an Android *user* home that
    // varies between CI runners, so every build ends up with a different signer and
    // `adb install -r` fails with a signature mismatch (wiping the app's data).
    // Pinning it to a property-defined file lets CI cache one keystore and keep a
    // stable signature across builds.
    val debugKeystorePath = providers.gradleProperty("meloxDebugStoreFile").orNull
    val meloxDebugSigning = if (debugKeystorePath != null) {
        signingConfigs.create("meloxDebug") {
            storeFile = file(debugKeystorePath)
            storePassword = providers.gradleProperty("meloxDebugStorePassword").orNull ?: "android"
            keyAlias = providers.gradleProperty("meloxDebugKeyAlias").orNull ?: "androiddebugkey"
            keyPassword = providers.gradleProperty("meloxDebugKeyPassword").orNull ?: "android"
        }
    } else null

    buildTypes {
        getByName("debug") {
            // Keep the upstream `.dev` suffix out: our debug build is signed with a
            // pinned keystore so `adb install -r` upgrades in place, and a package
            // rename would drop the imported LX scripts and the login state.
            meloxDebugSigning?.let { signingConfig = it }
        }
        getByName("release") {
            meloxReleaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/google/protobuf/**"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
        compilerOptions {
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

dependencies {
    implementation("xyz.gianlu.librespot:librespot-lib:1.6.5")
    implementation(project(":innertube"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Optional official Apple MusicKit for Android AARs. Download them from
    // Apple Developer and place only these two files in app/libs/:
    // musickitauth-release-*.aar and mediaplayback-release-*.aar.
    // The app remains catalog-capable when the optional files are absent.
    implementation(fileTree("libs") {
        include("musickitauth-release-*.aar", "mediaplayback-release-*.aar")
    })

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Kyant0/AndroidLiquidGlass (Backdrop). The high-level MeloX controls in
    // ui/glass are adapted from the project's official LiquidButton and
    // LiquidBottomTabs examples while preserving MeloX's iOS geometry.
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("io.github.kyant0:capsule:2.1.3")

    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.0")
    // LX Music-compatible user source scripts run in an isolated QuickJS context.
    // 3.2.0 is the first build with 16 KB page-size aligned native libraries;
    // 2.4.0 ships a libquickjs-android-wrapper.so whose LOAD segments are only
    // 4 KB aligned, which trips the compat warning on 16 KB devices.
    implementation("wang.harlon.quickjs:wrapper-android:3.2.0")
    // Force the newest graphics-path: older transitive versions ship a
    // libandroidx.graphics.path.so that fails the 16 KB alignment check.
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")

    // External lyric integrations.
    implementation("io.github.proify.lyricon:provider:0.1.70")
    // HyperOS Focus / Super Island notification payload builder.
    implementation("com.xzakota.hyper.notification:focus-api:1.4")
    // Optional non-root HyperOS compatibility path. If Shizuku is unavailable or
    // permission is denied, MeloX continues publishing Focus notifications directly.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Framework Binder signatures used only at compile time. Android supplies the
    // real hidden interfaces at runtime; this module is never packaged in the APK.
    compileOnly(project(":hidden-api"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
}
