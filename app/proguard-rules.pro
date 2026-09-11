# ==============================================================================
# BarPro SMS Forwarder - Production ProGuard & R8 Optimization & Obfuscation Rules
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Kotlin Metadata, Attributes, & Coroutines
# ------------------------------------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin reflection metadata necessary for Coroutines and reflection
-keepclassmembers class * {
    @kotlin.Metadata <fields>;
    @kotlin.Metadata <methods>;
}

-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# ------------------------------------------------------------------------------
# 2. Room Database: Entities, DAOs, and Database Classes
# ------------------------------------------------------------------------------
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Keep all app data local entities and DAOs
-keep class com.example.data.local.** { *; }
-keepclassmembers class com.example.data.local.** { *; }

# ------------------------------------------------------------------------------
# 3. Retrofit & OkHttp Networking Interfaces and JSON Models
# ------------------------------------------------------------------------------
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep interface com.example.network.** { *; }
-keep class com.example.network.** { *; }

# Retain all data models and JSON serialization field names
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** {
    <fields>;
    <methods>;
}

# Generic JSON & Pydantic-compatible serialization rules (Gson / Moshi / Kotlinx Serialization)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @kotlinx.serialization.SerialName <fields>;
}

# ------------------------------------------------------------------------------
# 4. Jetpack Compose Runtime and State
# ------------------------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# ------------------------------------------------------------------------------
# 5. DevSecOps: Cryptography, KeyStore, and Security Utilities
# ------------------------------------------------------------------------------
-keep class com.example.crypto.** { *; }
-keepclassmembers class com.example.crypto.** { *; }
-keep class com.example.utils.** { *; }
-keepclassmembers class com.example.utils.** { *; }
