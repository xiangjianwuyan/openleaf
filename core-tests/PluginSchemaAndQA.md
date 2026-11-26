# 青简 / OpenLeaf 声明式插件 v1

此处只涉及 OpenLeaf Android 应用。入口：「关于 → 应用插件 → 插件源」。插件不共享读者、编辑器、书源或操作系统插件权限。

## 清单契约

UTF-8（允许 BOM），最大 16 KiB。根为一个 JSON 对象，以下 6 字段均必填，禁止重复、额外字段与后续 JSON 文档。字段值类型严格检查：

| 字段 | 类型 | 约束 |
|---|---|---|
| `schema` | JSON 数字 | `1` |
| `id` | 字符串 | 2–4 段小写点分 ID；段以字母开始，包含小写字母、数字、`-`；每段最多 32 字符，整体最多 80；`openleaf.*` 保留给应用内置来源 |
| `name` | 字符串 | 1–48 Unicode 码点，非空白 |
| `description` | 字符串 | 1–180 码点，纯文本 |
| `capability` | 字符串 | 仅 `txt-report` |
| `prefixes` | 字符串数组 | 1–8 个不同字面前缀；每个 1–20 码点，不允许空白开头，不移除尾空格 |

不接受控制字符、双向显示控制符、未成对代理项或隐式执行字段（例如 `script`、`url`、`permissions`、`command`）。文本作为原生纯文字显示，不解析链接或标记。`txt-report` 只能读用户通过 Android 系统选择器本次明确选中的单一 UTF-8 文档（最多 1 MiB）。读取不保存 URI 或 SAF 持久授权；报告不保存、不分享、不上传。报告展示总行数、空行、非换行 Unicode 码点和标题候选计数；首尾换行不制造额外空行，空文档是 0 行。匹配大小写敏感、忽略行首空白、每行最多计一次，报告最多显示每行 80 字符、20 条示例。插件不会改源文件、书库、正文排版或实际章节目录。

外部 JSON 没有签名或代码执行权，来源「本地 JSON」表示用户从 SAF 选择，非认证发布者。内置样例来自应用内部，不读取外部站点；未实现 HTTPS 源或自动更新。文档提供者由系统选择器决定，可自己联网；OpenLeaf 不为插件发送请求。后台读取时会限制结果大小并向提供者请求取消，但无法强制不响应的提供者立即返回。取消后丢弃旧结果，不安装或运行。插件设置独立保存在本机，不在本轮书库备份范围。

## 样例和验收

导入样例：[PluginExample.json](<PluginExample.json>)。合成 UTF-8 文档内容：

```text
# Heading

prose
## Heading
```

1. 进入插件管理。插件源 → 内置目录 → 添加官方样例，检查弹框列出来源、ID、用途和边界；取消后仍空。再次确认安装，出现「已禁用」，不能运行。
2. 点击启用、重启应用，仍启用；点击「运行：选择 TXT / MD」，选上述文档。预期 4 行、1 空行、2 个标题候选（第 1/4 行）。取消选文件不运行。
3. 插件源 → 本地 JSON，选择本样例；预览标记为「本地 JSON」，安装默认禁用。启用并选择同一文档，预期仅第 4 行一个候选。由此确认声明实际改变工具行为，不只是设置展示。
4. 测试重复 `id`、保留 `openleaf.*`、未知字段 `url`、`schema: "1"`、重复字段、空 `prefixes`、大于 16 KiB、坏 UTF-8；均不得安装、不覆盖旧配置。可用 `prefixes:[".*"]` 验证元字符只按字面匹配。
5. 返回管理页依次禁用、重启确认不出现运行入口、移除、重启确认不存在；测试源文件本身不应变化。另用 1 MiB + 1 字节的 UTF-8 文档验证读取拒绝。

核心工具测试可独立以 JDK 17 运行：

```sh
javac --release 17 -encoding UTF-8 -d core-tests/plugin-build app/src/main/java/org/openleaf/reader/PluginTextReport.java core-tests/PluginTextReportTest.java
java -cp core-tests/plugin-build org.openleaf.reader.PluginTextReportTest
```
