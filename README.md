# LibCamera

Android 美颜滤镜库，基于 [GPUImage](https://github.com/cats-burg/android-gpuimage) 实现实时相机预览美颜、LUT 色彩滤镜、图片滤镜等功能。

## 功能特性

- **实时美颜**：磨皮、美白、红润、亮度、对比度、伽马、饱和度 7 项参数独立可调
- **LUT 滤镜**：30+ 内置 LUT 预设（黑白、清新、冰蓝、蜜桃、复古、电影、日系等）
- **滤镜风格**：9 种业务风格快捷切换，含推荐美颜参数
- **相机预览**：Camera1 API 封装，前后摄切换、实时滤镜预览、拍照
- **图片处理**：对 Bitmap 应用美颜 + 滤镜组合
- **自定义 GLSL**：内置 GPUImageBeautyFilter 自定义片段着色器
- **LUT 生成器**：512×512 标准 LUT 图生成工具

## 引入

### Gradle

```groovy
// 根 build.gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

// 模块 build.gradle
dependencies {
    implementation 'com.github.baiqingsong:lib-camera:1.0.0'
}
```

## 核心类说明

| 类名 | 说明 |
| --- | --- |
| `FilterManager` | 核心门面，管理 GPUImage 实例，支持图片滤镜、美颜处理 |
| `CameraFilterView` | FrameLayout 封装 GPUImageView，提供美颜 + 滤镜实时预览 |
| `CameraFilterHelper` | Camera1 生命周期管理，前后摄切换、拍照回调 |
| `BeautyParams` | 美颜参数 POJO（磨皮/美白/红润/亮度/对比度/伽马/饱和度） |
| `FilterStyle` | 9 种业务滤镜风格枚举 |
| `FilterPreset` | 30+ LUT 滤镜预设，支持缓存管理 |
| `FilterType` | 40+ 底层滤镜类型枚举 |
| `FilterFactory` | 工厂类，按 FilterType 创建 GPUImageFilter |
| `FilterSetting` | 滤镜类型 + 强度 POJO |
| `BeautyFilterPipeline` | GPUImageFilterGroup，组合美颜 + 风格滤镜 |
| `GPUImageBeautyFilter` | 自定义 GLSL 美颜着色器 |
| `LutGenerator` | 512×512 标准 LUT 位图生成 |

## 基本用法

### 相机实时预览

```java
// 布局中使用 CameraFilterView
CameraFilterView filterView = findViewById(R.id.camera_filter_view);
CameraFilterHelper helper = new CameraFilterHelper(context, filterView);

// 设置美颜 + 滤镜
BeautyParams params = BeautyParams.defaultCamera();
filterView.setBeautyAndFilter(params, FilterStyle.FRESH, 0.8f);

// 启动相机
helper.startCamera();

// 调整美颜参数（无需重建整个滤镜链）
params.setSmoothness(0.6f);
filterView.updateBeautyParams(params);

// 切换滤镜
filterView.updateFilterStyle(FilterStyle.ICE_BLUE, 0.7f);

// 拍照
helper.takePicture(bitmap -> { /* 处理照片 */ });

// 释放
helper.stopCamera();
```

### 图片滤镜

```java
FilterManager manager = new FilterManager(context);
Bitmap result = manager.applyFilter(sourceBitmap, FilterType.BEAUTY, 0.8f);
manager.release();
```

## 滤镜风格

| 风格 | 说明 |
| --- | --- |
| ORIGINAL | 原图（无滤镜） |
| FRESH | 清新 |
| ICE_BLUE | 冰蓝 |
| PEACH | 蜜桃 |
| TEXTURED_GRAY | 质感灰 |
| PERSONALITY | 个性 |
| COOL_WHITE_SKIN | 冷白皮 |
| ADVANCED_GRAY | 高级灰 |
| BLACK_WHITE_MOOD | 黑白情绪 |

