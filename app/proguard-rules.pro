# HammerScale ProGuard Rules
# Security and Obfuscation Configuration

# ======================
# KEEP RULES - Essential
# ======================

# Keep SecurityModule class and all its methods
-keep class com.nexora.hammerscale.SecurityModule { *; }
-keep class com.nexora.hammerscale.App { *; }
-keep class com.nexora.hammerscale.App$SecurityReport { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ======================
# OBFUSCATION RULES
# ======================

# Obfuscate package names
-repackageclasses ''

# Obfuscate aggressively
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# ======================
# SECURITY RULES
# ======================

# Prevent code reflection on sensitive classes
-keepclassmembers class com.nexora.hammerscale.SecurityModule {
    <fields>;
    <methods>;
}

# Keep native library loading
-keepclasseswithmembernames class * {
    native <methods>;
}

# ======================
# ANDROID SPECIFIC
# ======================

# Keep ViewBinding classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static ** inflate(...);
    public static ** bind(android.view.View);
}

# Keep data classes
-keep class com.nexora.hammerscale.model.** { *; }
-keep class com.nexora.hammerscale.databinding.** { *; }

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ======================
# NETWORK SECURITY
# ======================

# Keep network security config
-keep class com.nexora.hammerscale.network.** { *; }

# ======================
# LIBRARY RULES
# ======================

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX Core
-dontwarn androidx.core.app.CoreVerify
-dontwarn androidx.core.content.FileProvider

# ======================
# DEBUG SYMBOLS
# ======================

# Remove debug symbols in release
# (handled by native build config)
