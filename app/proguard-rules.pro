# This file was referenced by app/build.gradle.kts (proguardFiles(...)) but did not exist,
# which makes any `./gradlew assembleRelease` / `bundleRelease` fail immediately -- R8 can't
# find its own config file. debug builds never hit this because isMinifyEnabled = false there.
#
# The rules below aren't just "make it build" -- each one guards against a specific silent
# runtime break under obfuscation/shrinking that would otherwise show up as a confusing crash
# only in release builds, never in debug:

# --- TDLib (org.drinkless.tdlib) --------------------------------------------------------------
# The native (.so) side calls into these classes/fields by name via JNI. R8 has no way to see
# that from Kotlin/Java code alone, so without this it will happily rename or strip fields the
# native layer still expects -- this fails at runtime, not at build time, typically as a
# confusing native crash or a silently-empty TdApi.Object.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }

# --- Moshi (JSON models for the network layer) ------------------------------------------------
# Moshi's codegen/reflection needs constructors and field names intact for any data class it
# (de)serializes. Without this, minification can rename fields that no longer match the JSON
# keys coming back from the API -- fields silently deserialize to null instead of failing loudly.
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-dontwarn org.jetbrains.annotations.**

# --- Room ---------------------------------------------------------------------------------------
# Entities/DAOs are referenced by generated code via reflection-adjacent lookups in some Room
# versions; keeping them avoids obscure "no such column"/mapping failures that only show up
# once minification is on.
-keep class com.velastudio.teltv.data.local.** { *; }

# --- Media3 / ExoPlayer ---------------------------------------------------------------------
# Media3 ships its own consumer ProGuard rules via its AAR, so this is a light touch: only
# needed because we build a custom DataSource dynamically for TDLib playback.
-keep class com.velastudio.teltv.telegram.TdLibDataSource { *; }
-keep class com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory { *; }

# --- jcifs-ng / sardine-android (SMB + WebDAV) ------------------------------------------------
# Both parse server responses (SMB protocol structs / WebDAV XML) using their own model classes;
# keep them intact rather than debugging silent NAS/WebDAV browsing failures in release only.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**

# --- Kotlin coroutines / general -------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
