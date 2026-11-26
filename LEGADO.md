# OpenLeaf 0.10.2 · Legado 书源

在「发现 → 在线搜书 · Legado 书源」导入或粘贴 Legado JSON，选中书源并输入书名或作者。点开搜索结果即可查看详情、目录和正文。书源可以导出为 JSON，重复导入同一地址会保留已有配置。

书源格式参考 [rimvoid/legado](https://github.com/rimvoid/legado)，解析与界面由 OpenLeaf 独立实现。

- 导入接受单对象或数组，上限 100 条、512 KiB。不兼容的条目会在确认前提示。
- 支持文字书源、静态 HTTPS GET、`{{key}}` 和 `{{page}}`、常用 CSS、`@text` / `@href` 等提取规则，以及基础 JSONPath 字段、索引和 `[*]`。
- 支持详情页 `tocUrl` 和目录分页 `nextTocUrl`。正文通过目录进行章节切换，当前兼容范围为单页正文。
- JavaScript、XPath、正则替换、组合规则、登录、自定义请求头、POST、WebView 和分段正文暂未实现。发生重定向时请在配置中使用最终 HTTPS 地址。
- 搜索和阅读需连接配置的源。测试使用合成响应，第三方站点兼容性取决于其地址、规则和响应结构。
- 书源规则中的封面（`coverUrl`）会在搜索结果列表和详情页显示为缩略图：仅加载 HTTPS 地址，单张上限 1 MiB、最长边降采样到 360 px，失败时显示纯色占位，不阻塞搜索与阅读。封面只作展示，不写入书架书籍封面。
- 已知未接线字段：正文的 `nextChapterUrl` 会被解析，但翻章仍按目录索引切换，不会跟随 `nextChapterUrl`。配置时请保证目录列表完整。

## 书源配置示例

域名为占位地址。将其换成自己的 HTTPS 服务，并按页面结构修改选择器。

```json
{
  "bookSourceName": "示例文字书源",
  "bookSourceUrl": "https://books.example/",
  "bookSourceType": 0,
  "searchUrl": "/find?q={{key}}&page={{page}}",
  "ruleSearch": {"bookList":"@css:.book", "name":"a@text", "bookUrl":"a@href"},
  "ruleBookInfo": {"intro":".intro@text", "tocUrl":"a.toc@href"},
  "ruleToc": {"chapterList":"a.chapter", "chapterName":"text", "chapterUrl":"@href", "nextTocUrl":"a.next@href"},
  "ruleContent": {"content":"article@text"}
}
```

HTML 解析使用随源码提供的 jsoup 1.24.1，许可见 JAR 内的 `META-INF/LICENSE` 或 [jsoup 许可证](https://jsoup.org/license)。
