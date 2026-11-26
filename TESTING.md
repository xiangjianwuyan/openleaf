# 模拟器测试

后续默认在本机 **MuMu** 上测试青简，不使用真实手机。测试命令必须显式指定 MuMu 本地端口，禁止省略 `-s` 后让 ADB 自动选择设备，也不回退到 USB 手机。

## 发布前冒烟闸门（每轮必过，约 2 分钟）

**任何版本先过闸，再排细项验证。** 该闸门源于 0.30.0 的一次 P0：core 58/58 全绿、lint 0 error，但打开任何 TXT/MD/MOBI 书必崩（`colors()` 在 `text` 为 null 时被 `onResume→recomputeTheme` 调用）。纯 JVM 门禁与 lint 结构上覆盖不到 Activity 生命周期时序，只能靠实机开书暴露。

```
装包 → 开 TXT → 开 MOBI → 开 EPUB → 返回再开 → 查 logcat -b crash
```

判定：六个动作全部不崩，且 `adb -s <ip:port> logcat -b crash -d` 输出为空。

同类事故已出现 4 次（字体卡片不可滚动、按书对话框空列表、损坏字体不灰化、开书 NPE），共同点是"core 测试绿 ≠ UI 可用"，故固化为闸门而非一次性检查。

## 当前已验证环境

- MuMu 安装目录：`D:\Program Files\Netease\MuMu`。
- 实例：索引 **0**，名称“MuMu安卓设备”（Android **12 / API 32**）；索引 2 的 Android 15 实例 adbd 常处于 offline，不要使用。
- ADB 地址：`127.0.0.1:16384`（别名 `emulator-5554`）。端口可能变化，须以 `mumu-cli.exe info --vmindex 0` 当次查询为准。
- 实例未启动时：`mumu-cli.exe control --vmindex 0 launch`，待 `is_android_started=true` 后再 `adb connect`。
- 青简：0.42.0，versionCode 54。
- 注意：`adb` 与 JDK 的临时目录需指向可写位置（本机为 `deepseek-harness/default-workspace/.tools/tmp-build`），否则会出现 `cannot open adb.log: Permission denied`；APK 路径含中文时先复制到 ASCII 路径再 `install -r`。

## 当前工作区运行方式

以下命令从项目的父目录（工作区）运行。脚本位于工作区本地工具目录，不属于应用运行时：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .tools\run-mumu-tests.ps1 -VmIndex 2 -Install
```

脚本查询 `mumu-cli.exe info --vmindex 2`，要求实例已启动且报告本机回环 ADB 地址，再显式连接该实例。`-Install` 原地安装当前交付的 0.5.0 调试包；省略时只测试已安装应用。实例未启动时会报错，不自动启动其他实例，不修改模拟器配置。

旧的 `.tools\run-bulk-device.ps1` 已改为转发到同一个 MuMu 测试入口，移除了真实手机的硬编码序列号。脚本不仅检查 ADB 命令退出码，还检查输出包含 `PASS: 34 bulk integration checks`，避免 Android Instrumentation 报告失败却返回退出码 0 时误判。

## 其他开发机

在 MuMu 中启动要测试的实例，用该安装的 CLI 查询实际 ADB 端口，再运行：

```powershell
mumu-cli.exe info --vmindex 2
adb connect 127.0.0.1:16448
adb -s 127.0.0.1:16448 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 127.0.0.1:16448 shell am instrument -w org.openleaf.reader/org.openleaf.reader.BulkImportInstrumentation
```

示例中的端口必须换成查询所得端口；应用需要先完成 `assembleDebug`。不要使用示例连接真实手机。测试使用隔离书库和合成文档，不向正式书架添加1050本测试书，也不删除真实书籍。

## 本次结果

2026-11-20 在上述 MuMu 实例中成功安装、冷启动青简，**34 项隔离回归全部通过**，覆盖千本入库、去重、数据库升级、目录归档、自主整理和删除保护。首页控件层级检查确认“整理”“新建文件夹”“全部书籍”“扫描文件夹”和排序入口存在，应用进程保持运行。

本次 `uiautomator dump` 生成有效 XML 但返回了非零退出码；随后成功拉取并解析该文件。不能仅凭导出命令的退出码判断应用闪退。未声称已完成全部图形渲染、长时间稳定性或所有版本系统兼容性验收。
