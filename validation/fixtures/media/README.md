# 设备侧合成视频/字幕夹具（V1 视频页 UI 验收用）

## 为什么需要设备侧合成

`short-video.PLACEHOLDER.md` 仍有效：纯 JDK 17 环境没有 `MediaCodec`/`MediaMuxer`/`MediaMetadataRetriever`（Android framework API），也无 ffmpeg，无法合法合成可解码视频码流。本目录新增的 mp4/srt 是在**真机 Android runtime** 上用 `app_process` 直接运行 DEX 合成的，不经任何网络/用户文件。

## 夹具清单

| 文件 | 用途 | SHA-256 |
|---|---|---|
| `synthetic-video.mp4` | 480×320、H.264、30fps、**精确 3000ms**、ByteBuffer 输入逐帧显式 PTS、I 帧间隔 1s、46859B | `152ab43aec741c810c23042cf0e266f1390e7257affb168687beec8f58802cbc` |
| `synthetic-video.srt` | 3 条外挂字幕（中文 A `0.5–1.2s` / ASCII B `1.3–2.2s` / 中文 C `2.3–2.9s`） | `cedf905c1824982708f39de647ba7a6cafa4299c0413c8895821406364b5e0b2` |
| `frame-1000ms.png` | `MediaMetadataRetriever.getFrameAtTime(1000ms, OPTION_CLOSEST)` 参考静帧 | `47e07aada712cda861e05827709aa2de65cf8c6e37fd6dd507443459e6d57976` |
| `frame-1500ms.png` | 1500ms 参考静帧 | `2c04ad0fa521e7f2681a1a4e67032715add84ef2728121bf65528ceff4e6de38` |
| `frame-2500ms.png` | 2500ms 参考静帧 | `3de62274102edb4c5c5d9a5ce80b709b253cd10102e22f88e35ab8306acca200` |
| `VideoFixtureGenerator.java` | 设备侧生成器源码（仅测试工程，非生产代码） | 见 `MANIFEST.sha256` |

三张静帧 SHA **互不相同**，可作为同时间戳静帧对比基线（字幕安全区、无裁切、内容区像素零回归）。

## 固定时间戳视觉标记设计

- 背景色每秒不同（第 1/2/3 秒三种 RGB），便于粗略对齐；
- 中央用**纯矩形 7 段数码管**画大号 1/2/3（不用 `Canvas.drawText`——app_process 环境无 Typeface，文字渲染会触发 `gDefaultTypeface == nullptr` 断言崩溃）；
- 顶部黄色进度条宽度随帧号线性增长（精确到帧，1/30s）；
- 底部白色方块数 = 当前秒数。

## 复现步骤（真机，需 Android SDK + JDK17 + adb）

```bash
WS=<default-workspace>
JDK="$WS/.tools/jdk/jdk-17.0.21.1+2"
AJAR="$WS/.tools/android-sdk/platforms/android-36/android.jar"
D8="$WS/.tools/android-sdk/build-tools/35.0.0/d8.bat"
SRC="$WS/openleaf-ui-061/validation/fixtures/media/VideoFixtureGenerator.java"
OUT=<临时目录>

# 1. 编译 Java → class（android.jar 提供 MediaCodec/MediaMuxer 等 stub）
"$JDK/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$AJAR" -d "$OUT/classes" "$SRC"

# 2. d8 → DEX（注意包含匿名内部类 VideoFixtureGenerator$1.class）
"$D8" --lib "$AJAR" --output "$OUT/dex" "$OUT/classes/VideoFixtureGenerator"*.class

# 3. push 到设备并用 app_process 运行（无需安装 APK、不改任何包）
adb push "$OUT/dex/classes.dex" /data/local/tmp/videofixture/classes.dex
adb shell app_process -Djava.class.path=/data/local/tmp/videofixture/classes.dex \
  /data/local/tmp/videofixture VideoFixtureGenerator

# 4. 产物在设备 /sdcard/Download/ol-data01-probe/video-fixture/，pull 回本目录
# 5. 校验：cd validation/fixtures && sha256sum -c MANIFEST.sha256
```

## 设备编码器诊断（真机 74e7d510 / Android 17）

生成器会先枚举 `MediaCodecList(ALL_CODECS)` 的全部编码器并以 `VIDEOFIX ENCODERS_*` 打印。本机可用的 H.264 编码器含：

- `c2.qti.avc.encoder` / `OMX.qcom.video.encoder.avc`
- `c2.android.avc.encoder` / `OMX.google.h264.encoder`

生成器按支持 480×320 的最后一个可用 encoder 选中 `OMX.google.h264.encoder`；若设备无 H.264 encoder，明确输出 `FATAL no H.264 encoder...`，**不伪造可解码结论**。

## 关键技术点

- **不用 `createInputSurface()`**：Surface 输入的 PTS 默认取 wall-clock 时间（导致 duration 9757ms 且静帧同 SHA），且 `Surface.setTimestamp(long)` 是 @hide（SDK stub 与运行时均无此方法，反射 `NoSuchMethodException`）。改用 `MediaCodec` **ByteBuffer 输入 + `COLOR_FormatYUV420SemiPlanar`**，`queueInputBuffer(index, 0, yuv.length, us, 0)` 显式设置每帧 PTS，得到精确 3000ms。
- **Canvas 仅限几何绘制**：`Bitmap` + `Canvas.drawRect` 离线渲染 RGB，再手动转 NV12（Y 平面 + 交错 UV）。app_process 下禁用 `drawText`（Typeface 断言）。
- **MediaMetadataRetriever / MediaPlayer 数据源**：`setDataSource(String)` 与 `setDataSource(FileDescriptor)` 在 app_process 下均因 `FileUtils.getMediaProviderAppId()` 依赖 Context 而 NPE；改用 `MediaDataSource`（`RandomAccessFile` 实现）纯字节读取，无需 Context。

## 验证状态

- 设备侧 `MediaPlayer.prepare()` 成功、`getDuration()=3000`，`MediaMetadataRetriever` 读出 480×320 / 3000ms，三帧 SHA 互异。
- 证据与 OpenLeaf `MediaSourceActivity` 导入起播结论见 `deliverables/evidence-b064-device/video-fixture.md`。
