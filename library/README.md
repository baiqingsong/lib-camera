# LibCamera 视频功能使用说明

## 一、功能介绍

本库的视频能力基于 GPUImage 渲染管线实现，支持在**相机实时预览**和**外部视频流**两种输入源下录制视频。录制的每一帧都会经过 GPU 实时处理，自动叠加当前的美颜与滤镜效果，画面与预览所见一致。

### 核心特性

- **美颜 + 滤镜实时烘焙**：磨皮 / 美白 / 红润 / 亮度 / 对比度 / 伽马 / 饱和度 7 项美颜参数 + 9 种滤镜风格，逐帧 GPU 实时处理。
- **画面与预览一致**：旋转、前置镜像、手动翻转 / 90° 旋转均已烘焙进视频帧，不依赖播放器解析 orientation hint。
- **音视频同步**：
  - 视频：H.264（`video/avc`），4 Mbps，30 fps，独立 EGL + MediaCodec Surface 硬编；
  - 音频：AAC（44.1 kHz 单声道，128 kbps，AudioRecord 采集）。
- **输出格式**：MP4（MediaMuxer 封装）。
- **最长时长**：默认 30 秒自动停止（`CameraFilterHelper.MAX_RECORD_DURATION_MS`）。

### 两种输入源

| 输入源 | 说明 | 录像入口 |
| --- | --- | --- |
| 相机（CameraX） | 前后摄 / 外接 USB 摄像头实时预览 + 录像 | `CameraFilterView.startRecording` / `CameraSession.startRecording` |
| 外部视频流 | 任意帧源（其他相机 SDK、解码器、采集卡）逐帧喂 Bitmap | `CameraFilterView.startStreamRecording` / `VideoStreamRecorder` |

### 回调接口

所有回调均在**主线程**执行：

```java
// 相机录像
CameraFilterHelper.OnVideoRecordListener {
    void onVideoSaved(File videoFile);  // 录制完成，返回 MP4 文件
    void onError(String message);       // 录制失败 / 中断
}

// 视频流录像（语义相同）
VideoStreamRecorder.OnVideoRecordListener {
    void onVideoSaved(File videoFile);
    void onError(String message);
}
```

## 二、引用说明

### 1. JitPack 依赖（推荐）

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

### 2. 本地 AAR

将 `lib-camera-release.aar` 放入 `app/libs/`：

```groovy
dependencies {
    implementation files('libs/lib-camera-release.aar')
    // 传递依赖（必须）
    implementation 'jp.co.cyberagent.android:gpuimage:2.1.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
```

### 3. 初始化

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CameraKit.init(this);
    }
}
```

### 4. 权限

- 相机录像：`CAMERA` + `RECORD_AUDIO`
- 视频流录像：`RECORD_AUDIO`

库已内置权限检查与申请逻辑：
- 一体化控件模式自动申请相机权限；
- 录像时会检查 `RECORD_AUDIO`，若未授予会先申请并回调 `onError`，授权后需再次触发录像。

## 三、相机录像

### 方式 A：一体化控件（零样板代码）

```xml
<com.dawn.filter.CameraFilterView
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:autoManageLifecycle="true" />
```

```java
CameraFilterView cameraView = findViewById(R.id.cameraView);
cameraView.setBeautyAndFilter(BeautyParams.defaultCamera(), FilterStyle.FRESH, 0.8f);

// 开始录像（outputFile 传 null 自动生成到应用外部 Movies 目录）
cameraView.startRecording(null, new CameraFilterHelper.OnVideoRecordListener() {
    @Override public void onVideoSaved(File file) { /* 保存 MP4 */ }
    @Override public void onError(String message) { /* 失败 */ }
});

// 停止录像
cameraView.stopRecording();

// Activity 只需转发一行权限回调
@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    cameraView.onRequestPermissionsResult(requestCode, grantResults);
}
```

### 方式 B：CameraSession（手动生命周期）

```java
CameraKit.CameraSession session = CameraKit.get().newCameraSession(this, cameraView);

session.startRecording(null, new CameraFilterHelper.OnVideoRecordListener() {
    @Override public void onVideoSaved(File file) { /* 保存 MP4 */ }
    @Override public void onError(String message) { /* 失败 */ }
});
session.stopRecording();
```

## 四、视频流录像

适用于外部已有帧源的场景（其他相机 SDK、MediaCodec 解码、采集卡等）。

### 方式 A：控件喂帧

```java
cameraView.feedFrame(bitmap);  // 每帧调用，实时预览美颜 + 滤镜

cameraView.startStreamRecording(null, new VideoStreamRecorder.OnVideoRecordListener() {
    @Override public void onVideoSaved(File file) { /* 保存 MP4 */ }
    @Override public void onError(String message) { /* 失败 */ }
});
cameraView.stopStreamRecording();
```

### 方式 B：独立 VideoStreamRecorder（不依赖控件）

```java
VideoStreamRecorder rec = CameraKit.get().newStreamRecorder(new File(dir, "out.mp4"));
rec.prepare(BeautyParams.defaultCamera(), FilterStyle.ICE_BLUE, 0.7f, width, height,
        new VideoStreamRecorder.OnVideoRecordListener() {
            @Override public void onVideoSaved(File file) { /* 保存 MP4 */ }
            @Override public void onError(String message) { /* 失败 */ }
        });

rec.feedFrame(bitmap);                            // 每帧
rec.updateFilter(params, FilterStyle.PEACH, 0.8f); // 录制中动态换滤镜
rec.stop();                                       // 结束，结果通过 onVideoSaved 回调
```

## 五、注意事项

1. 独立 `VideoStreamRecorder` 传入的 `width/height` 需与 `feedFrame` 的 Bitmap 尺寸一致；控件喂帧方式会自动取首帧尺寸，无需指定。
2. `feedFrame` 传入的 Bitmap 由库内部做延迟安全回收，调用方**不得**再复用或 recycle 该 Bitmap。
3. 相机录像与视频流录像互斥，请勿同时使用。
4. 相机录像时长上限 30 秒，到点自动停止并回调 `onVideoSaved`。
5. 录像前若 `RECORD_AUDIO` 未授权，会先请求权限并回调 `onError`，用户授权后需再次触发录像。
