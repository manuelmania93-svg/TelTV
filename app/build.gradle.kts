plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.velastudio.teltv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.velastudio.teltv"
        minSdk = 21          // covers most Android TV boxes / sticks
        targetSdk = 34
        versionCode = 47
        versionName = "0.3.18"
    }

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

            signingConfigs {
        create("release") {
            storeFile = rootProject.file("teltv-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "YourSecurePassword123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "teltv"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "YourSecurePassword123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Compose for TV (leanback-style but modern, D-pad optimized)
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3")
    // NOTE: tv-foundation was previously listed here too but never actually used anywhere in
    // the codebase -- HomeScreen/BrowseScreen/SearchScreen already use plain
    // androidx.compose.foundation.lazy LazyColumn/LazyRow/LazyVerticalGrid, which is correct:
    // as of tv-foundation 1.0.0-alpha11 (the version that was pinned here), TvLazyColumn/
    // TvLazyRow/TvLazyVerticalGrid are themselves deprecated in favor of the plain Compose
    // Foundation containers, because Compose Foundation 1.7 absorbed the TV-aware
    // keep-focused-item-visible scrolling behavior directly. Pulling in the dependency for
    // nothing but its (deprecated) lazy layouts was dead weight -- removed.

    // Media playback - Media3 / ExoPlayer, HLS + progressive + subtitles
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("androidx.media3:media3-ui-leanback:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Networking - talks to the titan_vault backend REST/streaming API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Local persistence - library cache, watch history, resume positions, sources
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    // Lets Room DAOs return PagingSource directly (see VideoIndexDao.pagingSource) -- the core
    // piece of the "channel with 1000s of videos on a 2GB RAM TV" fix: only visible pages of a
    // channel's video list are ever loaded into memory, no matter how big the channel is.
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Paging3: backs the low-memory browse grid and drives it from Compose.
    implementation("androidx.paging:paging-runtime-ktx:3.3.2")
    implementation("androidx.paging:paging-compose:3.3.2")

    // Poster / thumbnail images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Direct NAS (SMB) and WebDAV sources, in addition to Telegram
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("com.github.thegrizzlylabs:sardine-android:0.8")

    implementation("com.jakewharton.timber:timber:5.0.1")

    // QR-code rendering for LoginScreen's QR sign-in step (offline, pure-Java bit-matrix
    // generation -- no camera/scanning code here, just `QRCodeWriter`, so the plain `core`
    // artifact is enough; no need for zxing-android-embedded).
    implementation("com.google.zxing:core:3.5.3")

    // Periodic background check that trims the Telegram file cache once it crosses the
    // configured size limit (see CacheManager). Also runs on app foreground.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    implementation(files("libs/tdlib.jar"))
}

// -----------------------------------------------------------------------------------------
// TDLib native library: intentionally NOT a Gradle dependency pulled from a random repo.
// Build it yourself from Telegram's official source (https://github.com/tdlib/td) using
// example/android/*.sh, then drop the output here:
//   app/src/main/jniLibs/arm64-v8a/libtdjni.so
//   app/src/main/jniLibs/armeabi-v7a/libtdjni.so
//   app/src/main/jniLibs/x86_64/libtdjni.so     (for emulator / Android TV boxes on x86)
//   app/src/main/java/org/drinkless/tdlib/*.java  (generated Client.java / TdApi.java)
// See /README.md for the full step-by-step.
//
// The check below exists purely so hitting Run before doing that gives ONE clear message
// ("TDLib isn't built yet, here's what's missing, see README") instead of a wall of
// "cannot resolve symbol org.drinkless.tdlib.*" spread across TelegramClient.kt,
// TdLibDataSource.kt, CacheManager.kt, ThumbnailLoader.kt, LoginScreen.kt, etc. It only
// checks file *presence*, not content -- it can't verify the sources actually match your
// TDLib build, that's still on you (e.g. matching the Kotlin `long[]` assumption on
// RequestQrCodeAuthentication.otherUserIds noted in LoginScreen.kt/TelegramClient.kt).
// -----------------------------------------------------------------------------------------
tasks.register("checkTdlibPresent") {
    doFirst {
        val requiredJavaSources = listOf(
            "libs/tdlib.jar"
        )
        val requiredAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

        val missingJava = requiredJavaSources.filterNot { file(it).exists() }
        val missingSo = requiredAbis.filterNot { file("src/main/jniLibs/$it/libtdjni.so").exists() }

        if (missingJava.isNotEmpty() || missingSo.isNotEmpty()) {
            val message = buildString {
                appendLine("TDLib hasn't been built into this project yet, so it can't compile.")
                appendLine()
                if (missingJava.isNotEmpty()) {
                    appendLine("Missing generated Java sources:")
                    missingJava.forEach { appendLine("  - app/$it") }
                }
                if (missingSo.isNotEmpty()) {
                    appendLine("Missing native libraries for: ${missingSo.joinToString(", ")}")
                    missingSo.forEach { appendLine("  - app/src/main/jniLibs/$it/libtdjni.so") }
                }
                appendLine()
                appendLine("Build TDLib for Android from Telegram's official source and drop the output")
                appendLine("into the paths above -- see README.md, \"Build TDLib for Android\" section, for")
                appendLine("the exact steps (check-environment.sh -> fetch-sdk.sh -> build-openssl.sh ->")
                appendLine("build-tdlib.sh from https://github.com/tdlib/td/blob/master/example/android).")
            }
            throw GradleException(message)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("checkTdlibPresent")
}


configurations.all {
    exclude(group = "xpp3", module = "xpp3")
    exclude(group = "stax", module = "stax-api")
    exclude(group = "xmlpull", module = "xmlpull")
}
