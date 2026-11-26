# 参与贡献

请先阅读 README 中的功能范围与格式边界。提交问题时说明 Android 版本、设备型号、操作步骤、预期与实际结果。若提供有问题的文件，请使用可公开授权的最小样本，不要上传私人书库。

开发环境为 JDK 17、Android SDK 35、Build Tools 35.0.0、Gradle 8.13。保持 API 26 兼容，不使用仅在较新系统提供的 Java 标准库方法；如确有需要，应另行配置并验证 desugaring。

提交前运行核心解析器测试、assembleDebug 和 lintDebug。解析、路径校验和数据持久化变更应增加覆盖实际边界的测试。界面变更应在至少一台设备或模拟器上检查文字放大、横屏、返回与进度恢复。

请保持变更范围清晰，勿提交生成文件、SDK、Gradle缓存、签名、访问令牌或受版权限制的书籍。新增依赖必须说明用途及许可。提交贡献表示你有权以本项目 MIT License 提供该贡献。

## 构建与测试

首次阅读 `LEARNING_PATH.md`（一页学习路径）与 `docs/adr/`（关键决策的为什么）。

Windows 下若系统 JDK 版本与 Gradle 不兼容，使用 JDK 17；核心测试无需模拟器，可直接用 javac/java 跑：

```
javac -encoding UTF-8 --release 17 -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/*.java core-tests/<测试名>.java
java -cp core-tests/build17 <测试名>
```

完整构建（含 lint）：`gradle clean assembleDebug lintDebug`。提交前所有 core 测试类全跑一遍，不只是新增的那一个。

## 体验改动的三条门禁

改动入口、面板、布局、动画等"迁移类"内容时，除构建与 core 测试外，还要过三条硬闸门：

1. 高频动作达到点按目标（对比迁移前后同一起始状态的次数，不得增加）。
2. 显隐工具/开关面板不导致正文、漫画页或播放位置意外跳动。
3. 既有能力逐项对照无丢失（入口位置可变，行为与可达性不可丢）。

细则、基线与独立签字要求见 `OpenLeaf-v2-发布门禁总纲.md`（工作区完整文档）与 `docs/adr/ADR-003-release-gates-and-baseline-first.md`。改动前先采基线；采不到标阻塞，不用源码说明冒充实机证据。

## 一个反复验证过的经验

core 全绿不代表 UI 可用。改某一处时，全仓 grep 所有调用点与入口对称检查（0.33 路由漏改、0.46 AMOLED 冷启降级都源于此）。详见 `LEARNING_PATH.md` 第 3、4 步。
