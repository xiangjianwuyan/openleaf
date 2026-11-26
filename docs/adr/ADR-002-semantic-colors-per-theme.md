# ADR-002：语义色令牌按主题分档映射

- 状态：已实施（0.46.0）
- 日期：2026-11-26
- 相关：`core/ThemePalette.java`、`ThemeResolver.java`、`core-tests/ThemePaletteTest.java`、`0.46-QA报告.md`、`0.47-AMOLED回归报告.md`

## 背景

借鉴 Material 3 Expressive 的"语义色角色"思想，要把散落在各 Activity 的硬编码颜色收进令牌。但 OpenLeaf 有 4 套主题（纸白/暖纸/夜间/AMOLED 纯黑 `#000000`），且 0.30 起还有独立的 8 色自定义纸色板。

如果令牌定义成固定 ARGB 常量表（M3 常见做法），切到 AMOLED 档时选中态、进度条、星标会在纯黑底上对比不足而不可读。

## 选项

- **A. 固定色令牌表**：实现简单，但 AMOLED 档不可读，违背 WCAG AA 4.5:1。
- **B. 按主题分档映射**：令牌是「角色 → 按 theme 取值」的函数，如 `accent(theme)` 在亮档返回暖棕、暗档返回高对比浅色。
- **C. 只给 Ui 加常量，不管主题**：等于没解决散落问题，且 AMOLED 档仍不可读。

## 决定

选 **B**：`ThemePalette` 新增 `surface/accent/onAccent/selectedContainer` 角色 API，全部内部 `clamp` 并按 theme 取值；亮档强调色 `0xFF44403C`、暗档 `0xFFC9C4B8`。4 档 × 4 组合实测对比度全部 ≥6.57:1（AMOLED 档 accent/surface 12.07:1）。

## 后果

- 正面：一处改色全主题生效；AMOLED 档可读性有量化证据；其他项目可复用"角色映射而非固定值"的思路。
- 负面：任何新角色都必须为 4 档各给一个值，不能只给一套——这是持续成本。
- **验证纪律**：`ThemePalette.lowContrast()` 现成可用，新增角色必须 4 主题档逐一走查对比度。

## 关联 P1（最重要的教训）

0.46 交付时 QA 真机发现：**AMOLED 冷启静默降级为夜间**——`ThemeResolver.java:29` 的私有 `clamp()` 还硬编码 `value > 2 ? 2`，没随 0.42 的 `PRESET_COUNT` 3→4 更新。会话内切档走 `% PRESET_COUNT` 能到 3，冷启走 resolver 被打回 2。

0.47 修复：resolver 的 clamp 委托 `ThemePalette.clamp()` 单一真源，follow=true 语义不变（跟随系统刻意不进 AMOLED）。

**核心教训**：同一口径的工具函数（clamp/resolve）不能有两份实现。改 `PRESET_COUNT` 这类常量时，必须全仓搜所有上界硬编码——这是"多入口对称检查"从 UI 入口扩展到口径函数的升级。
