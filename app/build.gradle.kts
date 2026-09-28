import java.net.URI
import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Needle 3 on-device engine (Apache-2.0, https://huggingface.co/Cactus-Compute/needle3).
// The static engine is pinned to one Hugging Face revision and verified by
// SHA-256 before CMake links it into libmeowclaw_needle.so.
val needleRevision = "b274efcb211a9eef48c9a88da4b43bd569696a39"
val needleEngines = mapOf(
    "arm64-v8a" to ("android-arm64" to "5e0a5daaadca1fbe1c518110bee6bbb1cc97a5e53a16a1e964d0af0186eac60a"),
    "armeabi-v7a" to ("android-armv7" to "40e8dcf4440281fc248302c25f0ddc87fcc9d52a210a216f06f2aad3de42c86c"),
)
val needleDir = layout.buildDirectory.dir("needle")

// Cactus on-device LLM runtimes (Apache-2.0), built from pinned sources by
// scripts/build-cactus.sh. Pass -PskipCactus for quick builds without them.
val cactusJniLibs = layout.buildDirectory.dir("cactus/jniLibs")
val buildCactus by tasks.registering(Exec::class) {
    description = "Builds the Cactus v2 and v1.14 runtimes for arm64-v8a."
    onlyIf { !project.hasProperty("skipCactus") }
    inputs.file(rootProject.file("scripts/build-cactus.sh"))
    outputs.dir(cactusJniLibs)
    commandLine(
        "bash", rootProject.file("scripts/build-cactus.sh").absolutePath,
        cactusJniLibs.get().asFile.absolutePath,
        layout.buildDirectory.dir("cactus/src").get().asFile.absolutePath,
    )
}

val downloadNeedleEngine by tasks.registering {
    description = "Downloads and verifies the prebuilt Needle 3 static engines."
    outputs.dir(needleDir)
    doLast {
        needleEngines.forEach { (abi, source) ->
            val (platform, sha256) = source
            val target = needleDir.get().file("$abi/libneedle.a").asFile
            fun digest() = MessageDigest.getInstance("SHA-256")
                .digest(target.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (target.isFile && digest() == sha256) return@forEach
            target.parentFile.mkdirs()
            val url = "https://huggingface.co/Cactus-Compute/needle3/resolve/$needleRevision/$platform/libneedle.a"
            logger.lifecycle("Downloading Needle engine for $abi")
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            check(digest() == sha256) { "Needle engine checksum mismatch for $abi" }
        }
    }
}

// Release signing: create keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) next to settings.gradle.kts. Without it, release
// builds fall back to the debug key, which Play Protect distrusts.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.farzanshibu.meowclaw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.farzanshibu.meowclaw"
        minSdk = 26
        targetSdk = 37
        versionCode = 3000
        versionName = "2.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DNEEDLE_LIB_DIR=${needleDir.get().asFile.absolutePath}"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("splitAbi")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testOptions {
        // android.view.KeyEvent constants are compile-time ints; other stubs return defaults.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets.getByName("main").jniLibs.directories.add(cactusJniLibs.get().asFile.absolutePath)

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.named("preBuild") { dependsOn(downloadNeedleEngine, buildCactus) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(downloadNeedleEngine) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.markdown.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
