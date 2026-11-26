# 第三方组件说明

OpenLeaf 原创源代码及内置 welcome.txt 按 MIT License 提供，完整文本见 LICENSE。

自 0.14.0 起，应用打包以下第三方组件；其余字体、书籍、统计或广告 SDK 仍不打包。Android 系统提供的 SDK API、PdfRenderer、WebView 和系统字体由设备系统提供，其许可不因本项目的 MIT License 改变。

### libmobi（MOBI / PRC / AZW / AZW3 解析）

- **许可证**：LGPL-3.0-or-later
- **版本**：v0.12，固定 commit `85dcfe803fc2a21020ddcf15c3eb66b93d388add`
- **来源**：https://github.com/bfabiszewski/libmobi
- **形态**：上游 C 源码原样随包（位于 `app/src/main/cpp/libmobi/`，零补丁），由 NDK `ndk-build` 编成独立动态库 `libopenleafmobi.so`，应用通过 `System.loadLibrary` **动态链接**加载。
- **许可证原文**：`THIRD_PARTY_LICENSES/libmobi-LGPL-3.0.txt`
- **源码 / 重建 / 替换 so 说明**：`THIRD_PARTY_LICENSES/libmobi-SOURCE-OFFER.md`（含固定 SHA、NDK 版本、ABI 与逐步重建命令）
- **DRM 边界**：libmobi 上游自带的加密文档 / Kindle eInk 加密能力**未被启用**；加密文档在解析前被硬拒绝，绝不调用解密接口。

### AndroidX Media3（播放器内核 / UI / 媒体会话）

- **许可证**：Apache-2.0
- **版本**：1.11.1（Q6 拍板的稳定版）
- **来源**：https://github.com/androidx/media
- **形态**：Gradle AAR，由 `google()` 仓库自动解析（传递依赖含 media3-common / exoplayer / ui / session / decoder / datasource / container / extractor / database / annotation / exifinterface 等，均为 Apache-2.0）
- **许可证原文**：`THIRD_PARTY_LICENSES/media3-APACHE-2.0.txt`
- **用途**：0.14.0 起本地音频、本地文件试听、媒体源音频流三处由 Media3 ExoPlayer 承载；`TtsActivity` 仍使用系统 TextToSpeech，不受影响。
- **DRM 边界**：仅播放设备与 Media3 支持的明文格式；不实现在线 DRM / 登录付费，不引入自适应流协议层。

### Junrar（CBR / RAR 漫画解压，0.34.0 起）

- **许可证**：Apache-2.0
- **版本**：7.5.3
- **来源**：https://github.com/junrar/junrar
- **形态**：Gradle JAR（`implementation 'com.github.junrar:junrar:7.5.3'`），传递依赖 `org.slf4j:slf4j-api:1.7.36`（MIT License）。
- **许可证原文**：`THIRD_PARTY_LICENSES/junrar-APACHE-2.0.txt`（slf4j 为 MIT，原文同目录 `slf4j-MIT.txt`）
- **用途**：阶段 2-B（Q3 采纳）漫画阅读器解压 CBR/RAR 归档；解压只列图片条目并逐页写出到私有缓存，复用 `ComicFlow.safeEntry` 路径白名单与 64MB/512MB 字节预算，防路径穿越与解压炸弹。
- **体积影响**：引入后 debug APK 由约 7.39MB 增至约 7.51MB（+0.11MB）；7z/CB7 未引入（后置），故未打包 commons-compress 等更重依赖。
- **能力边界**：junrar 7.5.3 **仅支持 RAR4**；RAR5（含明文）明确拒绝并提示转为 RAR4/CBZ/ZIP，不实现任何 DRM 绕过。

OpenLeaf 原创代码仍为 MIT，与 LGPL 组件以动态链接 + 独立 so 方式隔离，MIT 主体不被传染；Apache-2.0 组件按 Apache-2.0 条款独立声明，不混入 MIT。

构建使用 Android Gradle Plugin、Gradle 和 JDK，各自保留其原始许可。下载的 SDK 和本地工具不属于本项目原创源代码，也不随源码归档分发。后续引入任何库、字体、图标或书籍内容，应先评估许可证，并在此处补充名称、版本、来源与所需声明。

图标为本项目内创建的矢量叶片图形，没有使用第三方图标包。示例短文为本项目原创文本。
