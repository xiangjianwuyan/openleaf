# Native 构建链固定版本（T01 锁定）

| 组件 | 固定值 | 来源 / 校验 |
|---|---|---|
| NDK | `27.0.12077973`（android-ndk-r27-windows.zip） | `https://dl.google.com/android/repository/android-ndk-r27-windows.zip`；SHA1 `0ea2756e6815356831bda3af358cce4cdb6a981e`（与 Google `repository2-1.xml` 中 `ndk;27.0.12077973` 的 windows 条目一致）。AGP 8.13.2 的默认 NDK 版本也是 27.0.12077973（从 `gradle-8.13.2.jar` 的 `NdkLocatorKt.class` 实测得出）。 |
| libmobi | v0.12，commit `85dcfe803fc2a21020ddcf15c3eb66b93d388add` | `https://github.com/bfabiszewski/libmobi/archive/85dcfe803fc2a21020ddcf15c3eb66b93d388add.tar.gz`；SHA256 `5a4528a8aacc967a4ac65328c870dec5763fa7688be84faa47b9452f1e56e55c`。源码原样放在 `libmobi/`，零补丁。 |
| ABI | `arm64-v8a`, `armeabi-v7a` | `Application.mk:APP_ABI`；对应 §3.3 默认 Q5（不下载 CMake、不加 x86/x86_64）。 |
| 产物 | `libopenleafmobi.so` | `ndk-build` 输出 → `app/src/main/jniLibs/<abi>/`。实测：arm64-v8a **239,896 B**（ELF64/AArch64）、armeabi-v7a **147,564 B**（ELF32/ARM）；两 ABI 均导出 `Java_org_openleaf_reader_MobiNative_{available,version}`。 |
| minSdk 对齐 | `android-26` | `Application.mk:APP_PLATFORM`，与 `app/build.gradle:minSdk 26` 一致。 |

## 重建命令

```sh
cd app/src/main
<android-sdk>/ndk/27.0.12077973/ndk-build \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=cpp/Android.mk \
  NDK_APPLICATION_MK=cpp/Application.mk
# 产物位于 app/src/main/libs/<abi>/libopenleafmobi.so；
# 复制到 app/src/main/jniLibs/<abi>/ 即可随 APK 分发。
```

> 说明：本轮构建归属 QA；工程师侧仅做一次性 `ndk-build` 工具链验证并将产物放入 `jniLibs`。
> `HAVE_CONFIG_H` 未定义，因此不需要 autotools 生成的根 `config.h`；`USE_MINIZ` 已定义，
> 使用 libmobi 内置 miniz（见 `src/util.h`），不引入 zlib 外部依赖。
