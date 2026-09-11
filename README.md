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

### JitPack（推荐）

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

### 本地 AAR

将 `lib-camera-release.aar` 放入 `app/libs/`，然后：

```groovy
dependencies {
    implementation files('libs/lib-camera-release.aar')
    // lib-camera 依赖的传递依赖（必须）
    implementation 'jp.co.cyberagent.android:gpuimage:2.1.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
```

## 快速开始

### 1. 初始化（Application.onCreate）

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CameraKit.init(this);
    }
}
```

### 2. 相机实时预览（美颜 + 滤镜）

**布局：**
```xml
<com.dawn.filter.CameraFilterView
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

**Activity：**
```java
private CameraKit.CameraSession session;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_camera);

    CameraFilterView cameraView = findViewById(R.id.cameraView);

    session = CameraKit.get().newCameraSession(this, cameraView)
        .setBeautyAndFilter(BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f);
}

@Override
protected void onResume() {
    super.onResume();
    session.onResume();
    session.start();
}

@Override
protected void onPause() {
    super.onPause();
    session.onPause();
    session.stop();
}

// 切换滤镜
void switchFilter(FilterStyle style) {
    session.switchFilter(style, 0.8f);
}

// 调整美颜强度（单滑块控制）
void setBeautyIntensity(float intensity) {
    session.updateBeauty(CameraKit.beautyFromIntensity(intensity));
}

// 拍照
void takePhoto() {
    session.takePicture(bitmap -> {
        if (bitmap != null) {
            // bitmap 已包含美颜+滤镜效果
            saveToGallery(bitmap);
        }
    });
}

// 处理权限回调
@Override
public void onRequestPermissionsResult(int requestCode,
        @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    session.onRequestPermissionsResult(requestCode, grantResults);
}
```

### 3. Bitmap 离线处理

```java
// 美颜 + 滤镜一步到位
Bitmap result = CameraKit.get().processPhoto(
    original,
    BeautyParams.defaultCamera(),
    FilterStyle.ICE_BLUE,
    0.7f
);

// 仅美颜
Bitmap beautied = CameraKit.get().applyBeauty(original, BeautyParams.fromIntensity(0.6f));

// 仅滤镜
Bitmap filtered = CameraKit.get().applyFilter(original, FilterStyle.PEACH, 0.8f);

// 精细控制（复用 GPU 上下文）
FilterManager fm = CameraKit.get().newFilterManager();
Bitmap r1 = fm.applyBeauty(original, new BeautyParams(0.5f, 0.3f, 0.2f, 0.1f, 0.2f));
Bitmap r2 = fm.applyFilter(original, FilterStyle.BLACK_WHITE, 1.0f);
Bitmap r3 = fm.applyModules(original, BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f);
fm.release(); // 必须释放
```

### 4. 一体化控件（零样板代码）

`CameraFilterView` 支持自动管理模式：自动申请权限、自动跟随宿主生命周期启停相机，
外部只需一个控件 + 一行权限转发，无需再写 `onResume/onPause/start/stop`。

**布局（XML 开启自动管理）：**
```xml
<com.dawn.filter.CameraFilterView
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:autoManageLifecycle="true" />
```

**Activity（仅需一行权限转发）：**
```java
CameraFilterView cameraView = findViewById(R.id.cameraView);

// 设置美颜 + 滤镜
cameraView.setBeautyAndFilter(BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f);

// 拍照
cameraView.takePicture(bitmap -> saveToGallery(bitmap));

// 录像
cameraView.startRecording(null, new CameraFilterHelper.OnVideoRecordListener() {
    @Override public void onVideoSaved(File file) { /* 保存成功 */ }
    @Override public void onError(String message) { /* 失败 */ }
});
cameraView.stopRecording();

@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    cameraView.onRequestPermissionsResult(requestCode, grantResults); // 唯一需要转发的权限回调
}
```

### 5. 视频流输入（外部帧源）

当外部已有自己的帧源（其他相机 SDK、解码器、外接采集卡等），把每帧 Bitmap 喂给控件即可，
美颜 + 滤镜实时生效，支持截图和录像。

```java
// 每来一帧（线程安全，内部自动上屏）
cameraView.feedFrame(bitmap);

// 截图（含美颜+滤镜）
cameraView.takeSnapshot(result -> saveToGallery(result));

// 录像（含美颜+滤镜）
cameraView.startStreamRecording(null, new VideoStreamRecorder.OnVideoRecordListener() {
    @Override public void onVideoSaved(File file) { /* 保存成功 */ }
    @Override public void onError(String message) { /* 失败 */ }
});
// 停止录像
cameraView.stopStreamRecording();
```

也可以脱离控件，独立使用 `VideoStreamRecorder` 把帧直接编码成 MP4：

```java
VideoStreamRecorder rec = new VideoStreamRecorder(new File(dir, "out.mp4"));
rec.prepare(BeautyParams.defaultCamera(), FilterStyle.ICE_BLUE, 0.7f, 720, 1280, listener);
rec.feedFrame(bitmap);   // 每帧调用
rec.stop();              // 结束，结果通过 listener 回调
```

## 核心类说明

| 类名 | 说明 |
| --- | --- |
| `CameraKit` | **统一入口**，单例门面，封装初始化、Bitmap 处理、相机会话创建 |
| `CameraKit.CameraSession` | 相机实时预览的生命周期封装，管理美颜滤镜切换和拍照 |
| `FilterManager` | 图片处理核心，支持 Bitmap 美颜、滤镜、滤镜链 |
| `CameraFilterView` | FrameLayout 封装 GPUImageView，提供实时美颜+滤镜预览；支持一体化自动管理（权限/生命周期）与视频流输入（feedFrame） |
| `CameraFilterHelper` | Camera1 生命周期管理（前后摄切换、拍照回调） |
| `VideoStreamRecorder` | 视频流录像器，外部逐帧喂入 Bitmap 编码为含美颜+滤镜的 MP4 |
| `BeautyParams` | 美颜参数 POJO（磨皮/美白/红润/亮度/对比度/伽马/饱和度） |
| `FilterStyle` | 9 种业务滤镜风格枚举 |
| `FilterPreset` | 30+ LUT 滤镜预设，支持缓存管理 |
| `FilterType` | 40+ 底层滤镜类型枚举 |
| `FilterFactory` | 工厂类，按 FilterType 创建 GPUImageFilter |
| `FilterSetting` | 滤镜类型 + 强度 POJO |
| `BeautyFilterPipeline` | GPUImageFilterGroup，组合美颜 + 风格滤镜 |
| `GPUImageBeautyFilter` | 自定义 GLSL 美颜着色器（磨皮+美白+红润+亮度+对比度+伽马+饱和度） |
| `LutGenerator` | 512×512 标准 LUT 位图生成 |

## 美颜参数说明

| 参数 | 范围 | 说明 |
| --- | --- | --- |
| smoothness | 0.0~1.0 | 磨皮强度 |
| whiten | 0.0~1.0 | 美白强度 |
| rosy | 0.0~1.0 | 红润/肤色提亮 |
| brightness | 0.0~1.0 | 整体亮度 |
| contrast | 0.0~1.0 | 对比度 |
| gamma | 0.0~1.0 | 伽马校正（提亮暗部）|
| saturation | 0.0~1.0 | 饱和度 |

快捷构造方法：
- `BeautyParams.defaultCamera()` — 适合相机预览的默认值
- `BeautyParams.fromIntensity(float)` — 用单一数值 0~1 控制整体美颜程度

## 滤镜风格

| 风格 | 说明 |
| --- | --- |
| ORIGINAL | 原图（无滤镜） |
| FRESH | 清新 |
| ICE_BLUE | 冰蓝 |
| PEACH | 蜜桃 |
| COOL_WHITE_SKIN | 冷白皮 |
| ADVANCED_GRAY | 高级灰 |
| BLACK_WHITE | 黑白 |
| TEXTURED_GRAY | 质感灰 |
| PERSONALITY | 个性 |


