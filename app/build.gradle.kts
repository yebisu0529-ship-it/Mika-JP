plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// バージョン情報に現在のコミットの短いハッシュを表示する。Gitを利用できないソース配布物では dev とする。
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

// 公開用の秘密情報は環境変数からだけ受け取り、ソースやローカル設定へ書き込まない。
val releaseSigningNames = listOf("MIKA_RELEASE_KEYSTORE", "MIKA_RELEASE_STORE_PASSWORD", "MIKA_RELEASE_KEY_ALIAS", "MIKA_RELEASE_KEY_PASSWORD")
val releaseSigningValues = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val releaseSigningReady = releaseSigningValues.values.all { it != null }
if (releaseSigningValues.values.any { it != null } && !releaseSigningReady) {
    throw GradleException("公開用署名の環境変数が不足しています: " + releaseSigningValues.filterValues { it == null }.keys.joinToString())
}

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.studioyebisu.mika"
        minSdk = 30
        targetSdk = 35
        versionCode = 40
        versionName = "0.1.37-jp.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 正式版に移行したら、バージョン番号の後ろに付けるベータ表示を無効にする。
        buildConfigField("boolean", "BETA", "true")
    }

    // サンドボックスの実行プログラムは arm64-v8a のみ。他のABIでもuniversal APKを利用できるが、サンドボックスは利用できない。
    // nativeLibraryDir から直接実行するため、インストール時にネイティブライブラリをファイルへ展開する。
    packaging { jniLibs { useLegacyPackaging = true } }

    // perf/releaseでは、全ABIを含むuniversal APKと、小さいarm64-v8a APKを作る。
    // コード・リソース・バージョン・署名は共通で、他ABIの lib/ だけが異なる。どちらも単独でインストールできる完全なAPK。
    // 出力名は app-universal-<タイプ>.apk と app-arm64-v8a-<タイプ>.apk。debug単独ビルドでは従来の app-debug.apk を作る。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    // 同梱実行環境は tools/sandbox/build-runtime.py でxz圧縮済みのため、APK内で再圧縮しない。
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // リポジトリ直下の eval/tasks をテストAPKへ同梱し、実機評価ランナーから読み取る。
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    if (releaseSigningReady) {
        signingConfigs.create("publicRelease") {
            storeFile = file(releaseSigningValues.getValue("MIKA_RELEASE_KEYSTORE")!!)
            storePassword = releaseSigningValues.getValue("MIKA_RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("MIKA_RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("MIKA_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("publicRelease")
        }
        // devInstall指定時は、通常版と別IDで並行インストールし、通常版のデータを保持する。
        if (providers.gradleProperty("devInstall").isPresent) getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Mika Dev")
        }
        // 操作の滑らかさを確認するperf版は、releaseと同じくデバッグ不可で、性能測定を許可する。
        // debugと同じ署名でデータを保った更新ができる。公開用署名のreleaseとは別物。./gradlew :app:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 両方のOCRモデルをAPKに同梱し、初回利用時のPlay Servicesからのダウンロードを不要にする。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // coreのサンドボックス共通契約テストを、JVMと実機のAndroidSandboxでも利用する。
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit)
}

// LicensesTest等が参照するリポジトリ内の資料を、テストの更新判定に含める。
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("licenses/THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
    inputs.dir(rootProject.file("optional/china")).withPropertyName("optionalChinaFixtures")
}
