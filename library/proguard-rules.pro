# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# GPUImage - 保留 shader 和 OpenGL 相关代码
-keep class jp.co.cyberagent.android.gpuimage.** { *; }
-dontwarn jp.co.cyberagent.android.gpuimage.**

# Filter library - 保留对外公开 API（内部实现类允许混淆与裁剪）
-keep class com.dawn.filter.CameraKit { *; }
-keep class com.dawn.filter.CameraKit$CameraSession { *; }
-keep class com.dawn.filter.FilterManager { *; }
-keep class com.dawn.filter.CameraFilterView { *; }
-keep class com.dawn.filter.CameraFilterHelper { *; }
-keep class com.dawn.filter.CameraFilterHelper$OnPictureTakenListener { *; }
-keep class com.dawn.filter.CameraFilterHelper$OnVideoRecordListener { *; }
-keep class com.dawn.filter.CameraFilterHelper$OnFirstFrameListener { *; }
-keep class com.dawn.filter.BeautyParams { *; }
-keep class com.dawn.filter.FilterStyle { *; }
-keep class com.dawn.filter.FilterType { *; }
-keep class com.dawn.filter.FilterPreset { *; }
-keep class com.dawn.filter.FilterSetting { *; }
-keep class com.dawn.filter.FilterFactory { *; }
-keep class com.dawn.filter.BeautyFilterPipeline { *; }
-keep class com.dawn.filter.GPUImageBeautyFilter { *; }
-keep class com.dawn.filter.LutGenerator { *; }
-keep class com.dawn.filter.LutGenerator$ColorParams { *; }
-keep class com.dawn.filter.RetroFilter { *; }
-keep class com.dawn.filter.BlackWhiteMoodFilter { *; }
-keep class com.dawn.filter.TexturedGrayFilter { *; }
-keep class com.dawn.filter.PersonalityFilter { *; }

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