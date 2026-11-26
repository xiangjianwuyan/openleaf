# libmobi 源码提供与替换说明（LGPL-3.0-or-later）

OpenLeaf 0.14.0 起以**独立动态库** `libopenleafmobi.so` 的方式使用 libmobi，
通过 `System.loadLibrary("openleafmobi")` 加载。按 LGPL-3.0 的要求，本文件提供
源码地址、固定版本、重建步骤与替换 so 的操作说明。

## 1. 版本与来源（可复核）

| 项 | 值 |
|---|---|
| 项目 | libmobi — <https://github.com/bfabiszewski/libmobi> |
| 固定 tag | **v0.12** |
| 固定 commit | `85dcfe803fc2a21020ddcf15c3eb66b93d388add` |
| 源码压缩包 | <https://github.com/bfabiszewski/libmobi/archive/85dcfe803fc2a21020ddcf15c3eb66b93d388add.tar.gz> |
| SHA256 | `5a4528a8aacc967a4ac65328c870dec5763fa7688be84faa47b9452f1e56e55c` |
| 许可证 | LGPL-3.0-or-later（原文见 `libmobi-LGPL-3.0.txt`） |
| 补丁 | **无**。源码原样置于 `app/src/main/cpp/libmobi/`，未修改任何上游文件 |

LGPL-3.0 通过引用纳入 GPL-3.0 条款；完整 GPL-3.0 文本见随包 `gpl-3.0.txt`
（原见 <https://www.gnu.org/licenses/gpl-3.0.txt>，2026-11-23 获取，
SHA256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`）。

## 2. 随包提供的范围

- 完整 libmobi C 源码（17 个 `.c`、19 个 `.h`，含内置 miniz）。
- `app/src/main/cpp/Android.mk`、`Application.mk` — 固定 ABI 与编译参数的 ndk-build 脚本。
- `app/src/main/cpp/BUILD_VERSIONS.md` — NDK / commit / ABI / SHA 一览。
- `app/src/main/cpp/mobi_bridge.c` — OpenLeaf 自写的 JNI 桥（8 个 native 方法：available/version/nativeOpen/nativeTitle/nativeChapterCount/nativeChapterText/nativeIsEncrypted/nativeClose；仅解析，零解密 API）。

## 3. 重建步骤

```sh
# 1) 准备 NDK（版本固定）
#    SDK 目录：<android-sdk>/ndk/27.0.12077973/  （android-ndk-r27, SHA1 见 BUILD_VERSIONS.md）

# 2) 进入 app/src/main（Android.mk 与 Application.mk 在 cpp/ 下）
cd app/src/main

# 3) 用 ndk-build 编出两个 ABI 的 so（显式指定工程根目录与脚本路径，
#    避免 ndk-build 默认只找 jni/Android.mk 而失败）
<android-sdk>/ndk/27.0.12077973/ndk-build \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=cpp/Android.mk \
  NDK_APPLICATION_MK=cpp/Application.mk
#    产物：libs/arm64-v8a/libopenleafmobi.so
#          libs/armeabi-v7a/libopenleafmobi.so

# 4) 复制到 APK 打包目录
cp libs/arm64-v8a/libopenleafmobi.so   jniLibs/arm64-v8a/
cp libs/armeabi-v7a/libopenleafmobi.so jniLibs/armeabi-v7a/
```

`HAVE_CONFIG_H` 未定义，故无需 autotools 生成的根 `config.h`；`USE_MINIZ` 已定义，
使用 libmobi 内置 miniz（见 `src/util.h`），不依赖系统 zlib。`PACKAGE_VERSION=0.12` 在 Android.mk 中固定，
使 `mobi_version()` 返回真实版本而非 `unknown`。

## 4. 用户替换 so 的操作说明

LGPL 要求「可替换性」。OpenLeaf 不提供 so 热替换 UI，但 so 是可替换的：

1. 按第 3 节重新编译（或修改 `Android.mk` 后编译）得到 `libopenleafmobi.so`。
2. 用任意 ZIP 工具打开 APK，查看 `lib/arm64-v8a/` 与 `lib/armeabi-v7a/` 下的 so；
   替换为你自己编译的版本后重新签名即可安装。
3. 从源码构建整个应用时，只需把自编译 so 覆盖
   `app/src/main/jniLibs/<abi>/libopenleafmobi.so` 再执行常规构建。
4. JNI 接口共 8 个 native 方法：`available()` / `version()` /
   `nativeOpen` / `nativeTitle` / `nativeChapterCount` / `nativeChapterText` /
   `nativeIsEncrypted` / `nativeClose`；完整签名见
   `app/src/main/java/org/openleaf/reader/MobiNative.java`（与设计文档 §5.4 一致）。
   替换 so 时请保持这些符号签名不变。

## 5. DRM 边界声明

libmobi 上游具备加密文档处理 / Kindle eInk 加密能力，但 **OpenLeaf 未启用任何解密 API**：
解析前通过 `nativeIsEncrypted()` 前置硬拒绝加密文档（T02 实现），加密文档不入库、不解析。
