# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- ClipSync fork additions -------------------------------------------------
# Shizuku bridge talks to the platform clipboard service through reflection.
-keep class rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class android.content.IClipboard { *; }
-keep class android.content.IClipboard$* { *; }
-dontwarn rikka.shizuku.**

# Localisation tables are looked up by string key.
-keepclassmembers class com.aubynsamuel.clipsync.core.I18n { *; }

# kotlinx.serialization routes used by navigation-compose
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aubynsamuel.clipsync.ui.navigation.Screens$* { *; }
