# GPUImage - 保留 shader 和 OpenGL 相关代码
-keep class jp.co.cyberagent.android.gpuimage.** { *; }
-dontwarn jp.co.cyberagent.android.gpuimage.**

# Filter library - 保留公开 API
-keep class com.dawn.filter.** { *; }
-keep enum com.dawn.filter.FilterType { *; }
