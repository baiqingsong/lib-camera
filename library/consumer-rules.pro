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
-keep class com.dawn.filter.BeautyFilterPipeline { *; }
-keep class com.dawn.filter.LutGenerator { *; }
-keep class com.dawn.filter.LutGenerator$ColorParams { *; }
