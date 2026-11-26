# 音乐源与漫画源接口

本版新增「发现 → OPDS 目录 / 漫画源 / 音乐源 → 音乐源 / 漫画源接口」页面。可保存、移除、导入导出媒体源，分页搜索，播放歌曲或浏览漫画详情/章节/图片。音乐仅前台播放，支持暂停及进度拖动；退出页释放播放器。漫画按页加载，图片响应最多12 MiB并降采样，避免整章图片常驻内存。

文字书源入口独立位于「发现 → 在线搜书 · Legado 书源」，格式与规则见 [Legado 书源说明](LEGADO.md)；OPDS 目录则在「发现 → OPDS 目录 / 漫画源 / 音乐源」中浏览。

## 参考项目与兼容范围

研究了以下真实仓库资料，未复制它们的实现代码：

- [VeneraNext README](https://github.com/CyrilPeng/Venera-Next/blob/main/README.md)：JavaScript 漫画源扩展、搜索、详情、章节和图片请求。[最小源示例](https://github.com/CyrilPeng/Venera-Next/blob/main/doc/examples/minimal_source.js) 使用 `search.load(keyword, options, page)`、`comic.loadInfo(id)`、`comic.loadEp(comicId, epId)`；返回 `Comic`、`ComicDetails`、`images`，还可通过 `onImageLoad` 添加请求头。
- [XY-Music-Mobile README](https://github.com/kaishui-server/XY-Music-Mobile/blob/master/README.md)：Flutter/Rust 分层、音乐库及流式播放。[仓库音乐插件示例](https://github.com/kaishui-server/XY-Music-Mobile/blob/master/animemusic-wy-20260915.js) 定义 `plugin.call(action, params)`，含 `search` 和 `musicUrl`；搜索条目含 id/title/artist，解析后返回 url。

OpenLeaf 仍是原生 Java 应用；本版实现了**独立 JSON HTTP 适配协议**，没有嵌入两项目的 JavaScript/Rust 执行环境，不能直接导入任意 Venera 或 XY 的 JS 源。调用方可在自己的受信服务中运行或适配上游接口，再把结果转换为下述 JSON。没有内置第三方内容站。

`examples/media_source_server.py` 提供可直接运行的 stdlib 服务：默认生成原创两页PNG与三秒WAV，不需要外部内容站；可选 `--venera-api` 与 `--xy-api` 实现两份上游示例实际使用的 HTTP 返回结构转换。它适配的是示例所展示的 **HTTP 后端形状**，不声称兼容所有扩展或任意插件。

## 源包格式

源包为 UTF-8 JSON，最多256 KiB、100个源。格式标识为 `openleaf-media-sources`，schemaVersion=1。源 id 为稳定标识；导入时相同 id 保留已有配置，整包先校验再确认合并。

```json
{
  "format": "openleaf-media-sources",
  "schemaVersion": 1,
  "sources": [
    {
      "id": "my-comics",
      "name": "我的漫画服务",
      "kind": "comics",
      "baseUrl": "https://adapter.example/api/",
      "search": "search?q={query}&page={page}",
      "detail": "detail?id={id}",
      "pages": "pages?id={id}"
    },
    {
      "id": "my-music",
      "name": "我的音乐服务",
      "kind": "music",
      "baseUrl": "https://music.example/api/",
      "search": "search?q={query}&page={page}",
      "stream": "stream?id={id}"
    }
  ]
}
```

模板允许 `{query}`、`{id}`、`{page}`，值按UTF-8 URL编码，接口与 baseUrl 必须同源。接口、图片与音频地址需使用 HTTPS，不接受含账号或片段的URL。可跨域的图片/音频CDN地址不携带登录凭据。暂不支持自定义请求头、Cookie登录、DRM、付费获取、离线下载、歌词、脚本执行和后台音乐服务；需 Referer 的图片可由适配服务代理到可直接读取的 HTTPS 图片资源。

### 搜索（两类源共用）

请求 `GET search?q=关键词&page=1`，页码从1开始，每页最多500项：

```json
{"items":[{"id":"book-1","title":"一本漫画","author":"作者"}],"hasMore":false}
```

音乐将 author 改为 artist。id、title 必须为非空字符串。`hasMore` 控制下一页按钮；没有此字段时视为最后一页。

### 漫画详情与章节

`GET detail?id=book-1`：

```json
{"title":"一本漫画","description":"简介","chapters":[{"id":"book-1|chapter-1","title":"第一章"}]}
```

章节顺序按返回数组保留，最多2000章。章节 id 须能独立定位；适配服务可组合 comicId 与 chapterId。

`GET pages?id=book-1%7Cchapter-1`：

```json
{"pages":["https://cdn.example/page-1.png",{"url":"https://cdn.example/page-2.png"}]}
```

1–2000页，支持图片URL字符串或含url的对象；相对地址按 pages 响应请求地址解析。页面有上一页、下一页及返回章节按钮。

### 音乐播放解析

`GET stream?id=song-1`：

```json
{"url":"https://cdn.example/song-1.mp3"}
```

每次点击歌曲重新解析地址，适用于临时签名链接。播放能力受 Media3 / 设备解码器支持的格式限制（0.14.0 T02 起，本地音频由 Media3 ExoPlayer 承载）。服务错误用非2xx状态返回，应用显示错误并允许重试。

## 可运行示例与上游转换

先做不依赖外网的真实HTTP协议回归：

```powershell
python examples/media_source_server.py --self-test
```

该测试通过真实TCP HTTP请求执行搜索→详情→章节→两张PNG，以及音乐搜索→解析→WAV，另检查源包与第二页。这里HTTP只用于本地测试，Android正式客户端仍要求HTTPS。

部署服务给模拟器或手机访问时，使用客户端信任的域名和TLS证书：

```powershell
python examples/media_source_server.py --host 0.0.0.0 --port 18765 --public-base https://adapter.your-domain.example:18765 --cert fullchain.pem --key privkey.pem
```

从部署域名下载 `/source-pack.json`，在App导入后选择漫画或音乐，输入任意关键词搜索。默认返回明确标注的原创测试媒体，不是第三方内容搜索。证书须由系统信任，App没有跳过证书验证。

将示例服务连接到自己有权访问的上游：

```powershell
python examples/media_source_server.py --host 0.0.0.0 --public-base https://adapter.your-domain.example:18765 --cert fullchain.pem --key privkey.pem --venera-api https://your-comic-api.example/api --xy-api https://your-music-api.example/v1 --platform wy
```

Venera示例映射：

| OpenLeaf 路由 | 上游最小示例使用的HTTP路由 | 转换 |
| --- | --- | --- |
| comics/search?q=&page= | search?q=&page= | items保留id/title/author；totalPages转hasMore |
| comics/detail?id= | comics/{comicId} | chapters按数组原顺序，章id组合为comicId与chapterId |
| comics/pages?id= | comics/{comicId}/chapters/{chapterId} | images转pages |

XY示例映射：

| OpenLeaf 路由 | 上游插件示例使用的HTTP路由 | 转换 |
| --- | --- | --- |
| music/search?q=&page= | music/search?platform=&keyword=&page=&limit=30 | code=200后data条目转items；isEnd转hasMore |
| music/stream?id= | music/url?source=&musicId=&quality=320k | code=200后url转播放地址 |

上游服务需实现对应形状。示例没有使用仓库脚本内的具体IP或第三方默认服务；部署者自行配置合法的服务。其他源必须编写相应转换，或直接实现OpenLeaf JSON协议。

## Android 隔离回归

```powershell
adb -s 127.0.0.1:16448 shell am instrument -w org.openleaf.reader/org.openleaf.reader.MediaSourceInstrumentation
```

runner覆盖配置往返、请求编码与分页、漫画完整接口链路、音乐解析链路、取消与断网、源大小/条数/协议校验，以及真实MediaPlayer本地PCM准备、播放、暂停、跳转和释放。接口链路使用注入transport模拟HTTP响应，不依赖某个内容站；示例服务的self-test另覆盖真实HTTP。实际MuMu运行结果由交付验证记录记载，文档不把待运行的测试当作已通过。

## 第三方本地解析器（libmobi，0.14.0 T01 地基）

本地 MOBI / PRC / AZW / AZW3 文本解析由 **libmobi v0.12**（LGPL-3.0-or-later）承担，
不涉及上述任何 HTTP 媒体源协议，也不需要联网：

- 来源与固定 commit：`https://github.com/bfabiszewski/libmobi`，`85dcfe803fc2a21020ddcf15c3eb66b93d388add`
- 随包形态：原样 C 源码位于 `app/src/main/cpp/libmobi/`，经 NDK `ndk-build` 编为独立动态库 `libopenleafmobi.so`（ABI：arm64-v8a / armeabi-v7a）
- 许可原文 `THIRD_PARTY_LICENSES/libmobi-LGPL-3.0.txt`；源码 / 重建 / 替换软件说明 `THIRD_PARTY_LICENSES/libmobi-SOURCE-OFFER.md`
- libmobi 的 DRM 解密能力未启用；加密文档在解析前被拒绝，不入库、不解析。

> 播放能力文案已在 T02 随 Media3 引入同步更新。

## 第三方播放组件（AndroidX Media3，0.14.0 T02）

本地音频播放器（音频播放器 / 本地文件试听 / 音乐与漫画源音频流）自 0.14.0 T02 起由 **AndroidX Media3 1.11.1** 的 ExoPlayer 承载，
不涉及上述任何 HTTP 媒体源协议；媒体源 JSON 协议保持不变。

- 组件：`androidx.media3:media3-exoplayer` / `media3-ui` / `media3-session`（1.11.1，Q6 拍板稳定版）
- **许可证：Apache-2.0**；许可原文 `THIRD_PARTY_LICENSES/media3-APACHE-2.0.txt`，完整声明见 `THIRD_PARTY_NOTICES.md`
- 不引入在线 DRM、不登录付费、不新增常驻播放服务；`TtsActivity` 仍使用系统 TextToSpeech。
