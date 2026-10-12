import java.util.Properties

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
// Renamed to YSYY so this build can coexist with the upstream MeloX app
        // (com.lladlam.melox.android). The namespace stays com.lladlam.melox so the
        // Kotlin package layout is untouched.
        applicationId = "com.pala911.ysyy"
        // minSdk deliberately stays at 26. Upstream lowered it to 24 in 0.6.5, but the
        // LX/CHKSZ resolution pipeline this fork carries is only validated on 26+, and
        // widening the floor buys this build nothing (2026-10-12 upstream merge).
        minSdk = 26
        targetSdk = 37
        versionCode = 23
        versionName = "0.6.5"
        testInstrumentationRunner = "com.lladlam.melox.core.autosync.AutoSyncDeviceRunner"
        // One APK for real phones: both ARM architectures stay in the package,
        // so 32-bit devices keep working exactly as before. Only the
        // emulator-only x86/x86_64 copies are removed — ONNX Runtime alone
        // ships ~39 MB of native code per x86 ABI and no phone loads them.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
        // 方向锁总开关（默认 false = 锁竖屏，与 MeloX 主线一致）。
        // true 的变体（目前只有 debugPerf）会解除应用内横竖屏限制：
        //   · Manifest 的 MainActivity 不再写 portrait（改由 src/debugPerf/AndroidManifest.xml 覆盖）
        //   · MeloXApp 里那段「按播放页可见性切 SENSOR/PORTRAIT」的代码整个跳过
        // ⚠ 改这里会让 release/debug 跟着解锁 ⇒ 只允许在 buildType 里覆写，不要动这一行。
        buildConfigField("boolean", "UNLOCK_ORIENTATION", "false")
    }

    // ── Release signing ─────────────────────────────────────────────────────
    // Resolution order (first hit wins) for each value:
    //   1. -PmeloxRelease*  or  $GRADLE_USER_HOME/gradle.properties
    //   2. android/keystore.properties   (in-repo; copy keystore/ to build machines)
    //
    // Every release APK must be signed by exactly one certificate:
    //   CN=Oops MeloX,  SHA-256 0b3696412bf13ccaee032d2bd9672a2c729c86a4f8abc36a7c2cd59ec0fab44a
    // Android refuses to *update* an installed app whose new APK carries a
    // different key (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Mixing a throwaway
    // debug key with a real release key — as this project used to — makes every
    // install fail until the old package is uninstalled. Never swap this key.
    val meloxKeystoreProperties = rootProject.file("keystore.properties")
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

    fun meloxSigningValue(key: String): String? =
        providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
            ?: meloxKeystoreProperties?.getProperty(key)?.takeIf { it.isNotBlank() }

    val keystorePath = meloxSigningValue("meloxReleaseStoreFile")
    val keystorePassword = meloxSigningValue("meloxReleaseStorePassword")
    val keyAliasValue = meloxSigningValue("meloxReleaseKeyAlias")
    val keyPasswordValue = meloxSigningValue("meloxReleaseKeyPassword")
    val meloxReleaseSigning = if (
        keystorePath != null && keystorePassword != null && keyAliasValue != null && keyPasswordValue != null
    ) {
        signingConfigs.create("meloxRelease") {
            storeFile = rootProject.file(keystorePath)
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
            // minSdk is 26, so every device this app can run on understands APK
            // Signature Scheme v2 — AGP therefore never emits a v1 (JAR)
            // signature and silently ignores enableV1Signing. Spell out v2 + v3
            // explicitly anyway: v3 is what a hand-signed APK would carry and it
            // keeps key rotation possible. v4 (fs-verity) stays off — it needs a
            // matching .idsig and buys nothing for adb installs.
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
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
// Keep the upstream `.dev` suffix out, for two independent reasons:
            //  ① our applicationId is already com.pala911.ysyy, so it coexists with the
            //     official com.lladlam.melox.android without any suffix;
            //  ② this debug build is signed with the pinned keystore above and CI /
            //     local verification installs it with `adb install -r`, which only
            //     upgrades in place when the package id matches the release build
            //     (remember to pass -PmeloxDebugStoreFile or the signature differs).
            meloxDebugSigning?.let { signingConfig = it }
        }

        // ── debugPerf：不卡的 debug ──────────────────────────────────────────
        // 名字带 debug 前缀：它本质是 debug 变体的优化版，不是第三种发布形态。
        // 用户实测「debug 明显比 release 卡」。原因不是代码，是变体配置：
        //   ① debuggable=true ⇒ ART 关闭 JIT/AOT 与 profile-guided 优化，全程走
        //      解释器友好但慢的路径。Compose 全是小方法，受影响最大。
        //   ② debug 没有 R8 ⇒ 无内联、无死代码消除、保留全部 Compose 诊断开销。
        // release 两条都占了，所以流畅。
        // debugPerf 把这两条都打开，同时沿用 debug 的包名与 debug key ⇒ 可以直接
        // 覆盖安装替换掉卡顿的 debug 包，不用卸载、不丢数据。
        //   构建：./gradlew :app:assembleDebugPerf --offline --no-daemon
        //   装机：adb install -r app/build/outputs/apk/debugPerf/app-debugPerf.apk
        // 桌面应用名与 debug 同为「MeloX 测试版」（用户 2026-10-08 指定）：见
        // app/src/debugPerf/res/*/strings.xml。
        // ⚠ AGP sourceSet 不随 initWith 继承，目录名就是 buildType 名。
        create("debugPerf") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            // ── 解除横竖屏限制（2026-10-08 用户要求，仅此一版）────────────────
            // 对齐 MeloX 原版：不锁竖屏，方向完全交给系统 / 传感器。
            // 生效的两处：① src/debugPerf/AndroidManifest.xml 把 MainActivity 的
            // screenOrientation 从 portrait 改成 unspecified
            //            ② MeloXApp.kt 跳过运行时 SENSOR/PORTRAIT 强制切换
            // ⛔ 不要把这一行挪到 debug / release —— 只这一版解锁。
            buildConfigField("boolean", "UNLOCK_ORIENTATION", "true")
            // ⚠ 核心开关：ART 恢复优化 + 手写 Baseline Profile 才会被真正用于 AOT。
            isDebuggable = false
            isMinifyEnabled = true
            // :baselineprofile（com.android.test）只暴露 dev / release 两种变体，
            // 新增的 debugPerf 必须声明回退，否则 baselineProfile 依赖解析直接失败。
            matchingFallbacks += listOf("dev", "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        getByName("release") {
            meloxReleaseSigning?.let { signingConfig = it }

            // ── Package id ──────────────────────────────────────────────────
            // The official MeloX already owns com.lladlam.melox.android under
            // upstream's signing key, and Android refuses to update an app whose
            // new APK carries a different key. Keeping the bare id here means
            // every release install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE
            // for as long as the official build is present — a fork can never be
            // a clean in-place update. A distinct id makes each future release a
            // safe update, permanently. Manifest authorities are all
            // ${applicationId} templates, so nothing else has to change.
            // Override with -PmeloxReleaseIdSuffix= (empty) to target the
            // official id instead.
            val releaseIdSuffix = providers.gradleProperty("meloxReleaseIdSuffix").orNull
                ?: ".fork"
            if (releaseIdSuffix.isNotBlank()) {
                applicationIdSuffix = releaseIdSuffix
            }

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

    // The bottom-chrome animation runs a per-frame Compose semantics diff
    // whenever a third-party accessibility service is bound. Device-driven
    // BaselineProfileGenerator is unusable on the target vivo (instrumentation
    // crashes, logcat blocked), so a hand-authored profile covering the
    // animation / recomposition / semantics / glass-shader hot path is
    // shipped instead. Regenerate with `tmp/gen_baseline_profile.py`.
    baselineProfile {
        saveInSrc = true
    }

    // Feed the authored profile into every variant that ships to a device.
    sourceSets {
        getByName("main") {
            resources.srcDir("src/main/baselineProfiles")
            // Translations live outside `res/` so a contributor can add one folder
            // per language without touching code or the default strings.
            // See `android/app/i18n/README.md`.
            res.srcDir("i18n")
        }
    }

    // ── Never emit an unsigned release APK ──────────────────────────────────
    // Without a signing config AGP still reports success and writes
    // `app-release-unsigned.apk`, which can never be installed on any device.
    // That silent downgrade is exactly what made release installs fail.
    // Fail loudly and early instead, with instructions on how to supply the key.
    val meloxSigningHint = """
        Release build requested but no signing material is available.
        Provide one of the following:
          - android/keystore.properties (copy it plus the keystore/ directory to this machine)
          - -PmeloxReleaseStoreFile=... -PmeloxReleaseStorePassword=... -PmeloxReleaseKeyAlias=... -PmeloxReleaseKeyPassword=...
          - the same four keys in ${'$'}GRADLE_USER_HOME/gradle.properties
        Refusing to build an unsigned release APK.
    """.trimIndent()

    val meloxReleaseTaskRequested = gradle.startParameter.taskNames.any { task ->
        val name = task.substringAfterLast(':').lowercase()
        name.contains("release") || name == "build" || name == "bundle" ||
            name == "install" || name == "publish"
    }

    if (meloxReleaseSigning == null && meloxReleaseTaskRequested) {
        throw GradleException(meloxSigningHint)
    }

    // Belt and braces: the configure-time check above is skipped by tooling that
    // does not populate startParameter (notably IDE sync). Anchor a guard on the
    // release packaging tasks themselves.
    tasks.matching {
        it.name == "preReleaseBuild" || it.name == "assembleRelease" ||
            it.name == "bundleRelease" || it.name == "packageRelease"
    }.configureEach {
        doFirst {
            if (meloxReleaseSigning == null) throw GradleException(meloxSigningHint)
        }
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

    // AutoSync word-level lyric alignment. Inference is fully local (ONNX
    // Runtime on CPU); weights are supplied per language, never bundled here.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.0")
    // LX Music-compatible user source scripts run in an isolated QuickJS context.
// 3.2.0 was the first build with 16 KB page-size aligned native libraries;
    // 2.4.0 ships a libquickjs-android-wrapper.so whose LOAD segments are only
    // 4 KB aligned, which trips the compat warning on 16 KB devices. Upstream is on
    // 3.2.3 now, which keeps that property, so take theirs.
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
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
    // bzip2 decoder for the BSDIFF40 incremental-update patches.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Framework Binder signatures used only at compile time. Android supplies the
    // real hidden interfaces at runtime; this module is never packaged in the APK.
    compileOnly(project(":hidden-api"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
}
