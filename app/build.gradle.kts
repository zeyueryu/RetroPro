import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，无需再声明 kotlin.android
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 本机私有配置（签名密钥 / SDK 路径）——**绝不入库**
//
// 取值顺序：local.properties（已 gitignore）→ 环境变量。
// 四个 REPRO_* 只要缺一个，就**整个跳过签名配置**：这样别人 clone 下来
// 依然能 assembleDebug 出未签名包，不会因为拿不到密钥而构建失败。
//
//   # local.properties 示例（注意：路径用正斜杠，反斜杠会被 Properties 当转义符）
//   REPRO_STORE_FILE=C:/path/to/retropro-release.p12
//   REPRO_STORE_PASSWORD=****
//   REPRO_KEY_ALIAS=retropro
//   REPRO_KEY_PASSWORD=****
// ---------------------------------------------------------------------------
val localProps = Properties().also { props ->
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use { props.load(it) }
}

fun propOrEnv(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val signingSecrets = listOf(
    "REPRO_STORE_FILE",
    "REPRO_STORE_PASSWORD",
    "REPRO_KEY_ALIAS",
    "REPRO_KEY_PASSWORD",
).associateWith { propOrEnv(it) }

/** 四项密钥信息齐全才启用签名 */
val hasSigningConfig: Boolean = signingSecrets.values.none { it.isNullOrBlank() }

/** 密钥库绝对路径（缺失时为 null） */
val keystoreFile: File? =
    signingSecrets["REPRO_STORE_FILE"]?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

val useSigning = hasSigningConfig && keystoreFile != null

android {
    namespace = "com.retropro"
    // 依赖（Compose 1.12.x / core-ktx 1.19 / materialkolor 5.0.1）强制要求 compileSdk >= 37。
    // 本机平台目录为 android-37 / android-37.0，ApiLevel 形如 "37.0"，需显式声明 minor = 0 才能匹配。
    compileSdk = 37
    compileSdkMinor = 0

    // ---- 签名配置（密钥与密码全部来自 local.properties / 环境变量，源码里不出现明文）----
    // 自签名证书；本项目发布用的证书有效期 2026-09-26 ~ 2051-05-18。
    signingConfigs {
        if (useSigning) {
            create("retropro") {
                storeFile = keystoreFile
                storeType = "PKCS12"
                storePassword = signingSecrets["REPRO_STORE_PASSWORD"]
                keyAlias = signingSecrets["REPRO_KEY_ALIAS"]
                keyPassword = signingSecrets["REPRO_KEY_PASSWORD"]
                // v3 的签名块嵌在 v2 块内部，三者必须同时开启，缺 v2 时 v3 不生效
                enableV1Signing = true // JAR signing，兼容旧安装器
                enableV2Signing = true // APK Signature Scheme v2
                enableV3Signing = true // APK Signature Scheme v3，支持 key rotation
            }
        }
    }

    // ★ AGP 9.4.1 实测（javap 逐层核实）：签名开关的数据链是
    //   SigningConfigImpl(Property) → writeXxxSigningConfigVersions → signing-config-versions.json
    //   → IncrementalPackagerBuilder → SignedApkOptions，variant API 的 Property.set() 能写进 JSON；
    //   但打包器在 minSdk 较高时**仍只落最高档签名块**（minSdk=33 实测：v1/v2/v3 全 true 也只出 v3；
    //   对照实验 debug：v2=true+无v3 → 只出 v2）。DSL/variant API 都压不过这条打包器侧的取舍。
    //   ⇒ 要精确拿到 v1+v2+v3，用 apksigner CLI 对打包产物**显式后签**（下方 signReleaseV123 任务）。
    androidComponents {
        onVariants { variant ->
            variant.signingConfig?.let { sc ->
                sc.enableV1Signing.set(true) // JAR signing
                sc.enableV2Signing.set(true) // APK Signature Scheme v2
                sc.enableV3Signing.set(true) // APK Signature Scheme v3
                sc.enableV4Signing.set(false) // 只按需求开 v1/v2/v3
            }
        }
    }

    defaultConfig {
        applicationId = "com.retropro"
        // 支持 Android 12（API 31）：RenderEffect 模糊在 31 就有，RuntimeShader 折射要 33。
        // 差异由 GlassCapability 在运行期分档消化，见 com.retropro.glass。
        minSdk = 31
        targetSdk = 36
        versionCode = 48
        versionName = "1.1.15-m1"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug 也用同一把密钥：保证「debug 签的旧包 → release 签的新包」可以直接覆盖安装
            // （Android 按签名证书判定覆盖安装，换证书会 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
            if (useSigning) signingConfig = signingConfigs.getByName("retropro")
        }
        release {
            if (useSigning) signingConfig = signingConfigs.getByName("retropro")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ---- MIUIX 设计语言（Xiaomi HyperOS 风格，Apache-2.0）----
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // ⚠️ 不再引入 miuix-blur：它声明 minSdk 33，会把整个 App 的 minSdk 顶到 33。
    //    且它**从未接入代码**（玻璃的 MIUIX_BLUR 档本就退化为纯色）。
    //    将来要用必须先在 manifest 里 overrideLibrary，且只能在 API 33+ 分支调用。
    implementation(libs.miuix.squircle)

    // ---- 液态玻璃材质 ----
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.glass)
    implementation(libs.backdrop)

    // ---- 本地语音识别（sherpa-onnx + SenseVoice-Small int8，模型来自魔搭镜像）----
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // ---- 本地数据库（Room + KSP）----
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ---- 协程 ----
    implementation(libs.kotlinx.coroutines.android)

    // ---- Lifecycle / ViewModel ----
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

// Room 导出的 schema：纳入版本管理，便于后续写迁移时对比
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// ---- apksigner 显式后签：精确产出 v1+v2+v3 三签名块 ----
// AGP 打包器在高 minSdk 下只落最高档签名块（见上方 androidComponents 注释），
// apksigner CLI 的 --vN-signing-enabled 是硬开关，按参数逐档写入，不做 minSdk 取舍。
//
// 密码同样只从 local.properties / 环境变量取；没配密钥（别人 clone 的场景）就不注册这个任务。
val sdkDir: String? = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
val versionTag = android.defaultConfig.versionName ?: "dev"

// apksigner 跨平台：Windows 是 apksigner.bat（必须经 cmd /c 调用），
// Linux/macOS 是无扩展名的 shell 脚本（直接执行）。CI（ubuntu runner）走后者。
val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
val apksignerExe = "$sdkDir/build-tools/37.0.0/" + if (isWindows) "apksigner.bat" else "apksigner"
val apksignerCmd: List<String> =
    if (isWindows) listOf("cmd", "/c", apksignerExe) else listOf(apksignerExe)

val signReleaseV123 = if (useSigning && sdkDir != null) {
    tasks.register("signReleaseV123", Exec::class) {
        group = "build"
        description = "用 apksigner 对 release 包显式签出 v1+v2+v3（AGP 打包器在 minSdk>=33 时只写 v3）"
        dependsOn("packageRelease")

        val inApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
        val outApk = layout.buildDirectory.file("outputs/apk/release/RetroPro-$versionTag-signed.apk")
        inputs.file(inApk)
        outputs.file(outApk)

        val keystorePath = keystoreFile!!.absolutePath
        val storePass = signingSecrets["REPRO_STORE_PASSWORD"]!!
        val keyPass = signingSecrets["REPRO_KEY_PASSWORD"]!!
        val alias = signingSecrets["REPRO_KEY_ALIAS"]!!

        commandLine(apksignerCmd + listOf(
            "sign",
            "--ks", keystorePath,
            "--ks-key-alias", alias,
            "--ks-pass", "pass:$storePass",
            "--key-pass", "pass:$keyPass",
            // v1/v2/v3 全开、v4 关闭
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", outApk.get().asFile.absolutePath,
            inApk.get().asFile.absolutePath,
        ))
    }
} else {
    null
}

signReleaseV123?.let { task ->
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        finalizedBy(task)
    }
}
