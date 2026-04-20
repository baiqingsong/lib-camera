# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# GPUImage - 保留 shader 和 OpenGL 相关代码
-keep class jp.co.cyberagent.android.gpuimage.** { *; }
-dontwarn jp.co.cyberagent.android.gpuimage.**

# Filter library - 保留公开 API
-keep class com.dawn.filter.** { *; }
-keep enum com.dawn.filter.FilterType { *; }

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