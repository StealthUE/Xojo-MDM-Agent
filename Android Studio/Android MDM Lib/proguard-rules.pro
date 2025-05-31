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
# Preserve security-crypto classes
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Preserve KeyStore and related classes
-keep class java.security.** { *; }
-keep class javax.crypto.** { *; }
-dontwarn java.security.**
-dontwarn javax.crypto.**

# Preserve Android KeyStore classes
-keep class android.security.keystore.** { *; }
-dontwarn android.security.keystore.**