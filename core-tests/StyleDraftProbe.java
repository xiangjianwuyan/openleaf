package org.openleaf.reader;

import org.openleaf.reader.core.ReaderPresets;

import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;

/**
 * 0.57.0 P1-2 StyleDraft javac 探针（仅测试代码，不依赖 JUnit / Android 运行时实现）。
 *
 * <p>目的：证明「from → 改字段 → copyInto → equals」的草稿语义成立：
 * 草稿阶段的任何改动都不改变原始 ReaderStyle 的持久化字段，也不触发任何
 * SharedPreferences.edit()；只有显式 copyInto() 才把 8 个 style_* 档位写回内存。</p>
 *
 * <p>编译/运行（android.jar 仅提供接口符号，构造器用反射 + 动态代理注入假 prefs）：
 * <pre>
 *   javac -encoding UTF-8 --release 17 -cp android.jar -d out \
 *         ReaderStyle.java StyleDraft.java ReaderPresets.java StyleDraftProbe.java
 *   java -cp out;android.jar org.openleaf.reader.StyleDraftProbe
 * </pre>
 */
public final class StyleDraftProbe {
    private static int checks = 0;
    private static int edits = 0;

    private StyleDraftProbe() { }

    public static void main(String[] args) throws Exception {
        SharedPreferences prefs = stubPrefs();
        Constructor<ReaderStyle> constructor =
                ReaderStyle.class.getDeclaredConstructor(SharedPreferences.class);
        constructor.setAccessible(true);
        ReaderStyle style = constructor.newInstance(prefs);

        check(style.stylePreset == ReaderPresets.STANDARD, "基线 style_preset=标准");
        check(style.lineSpacing == 2 && style.paraSpacing == 1 && style.sideMargin == 2,
                "基线 7 项档位按 prefs 读取");

        int[] before = snapshot(style);
        StyleDraft draft = StyleDraft.from(style, true, 22);
        StyleDraft twin = StyleDraft.from(style, true, 22);
        check(draft.preset() == style.stylePreset, "from: preset 快照一致");
        check(draft.lineSpacing() == style.lineSpacing
                && draft.paraSpacing() == style.paraSpacing
                && draft.letterSpacing() == style.letterSpacing
                && draft.sideMargin() == style.sideMargin
                && draft.align() == style.align
                && draft.indent() == style.indent
                && draft.convert() == style.convert, "from: 7 项档位快照一致");
        check(draft.paragraphReflow() && draft.fontSize() == 22, "from: 重排/字号快照一致");
        check(draft.equals(twin), "equals: 同值草稿相等");

        draft.applyPreset(ReaderPresets.COMPACT);
        check(draft.preset() == ReaderPresets.COMPACT, "applyPreset: 档位标题=紧凑");
        check(draft.lineSpacing() == 0 && draft.paraSpacing() == 0
                && draft.letterSpacing() == 0 && draft.sideMargin() == 0
                && draft.align() == 0 && draft.indent() == 0 && draft.convert() == 0,
                "applyPreset: 7 项索引来自 ReaderPresets.valuesFor");
        check(sameStyle(style, before), "预设改动不污染原始 ReaderStyle");

        draft.setFontSize(26);
        draft.setParagraphReflow(false);
        draft.setLineSpacing(4);
        draft.markCustom();
        check(draft.fontSize() == 26 && !draft.paragraphReflow(), "setFontSize/setParagraphReflow 生效");
        check(draft.lineSpacing() == 4, "setLineSpacing 生效");
        check(draft.preset() == ReaderPresets.CUSTOM, "markCustom: 微调后回到自定义");
        check(!draft.equals(twin), "equals: 改动后不相等");
        check(edits == 0, "草稿阶段零 SharedPreferences.edit()");
        check(sameStyle(style, before), "所有草稿改动均未写回 ReaderStyle");

        draft.copyInto(style);
        check(style.stylePreset == draft.preset()
                && style.lineSpacing == draft.lineSpacing()
                && style.paraSpacing == draft.paraSpacing()
                && style.letterSpacing == draft.letterSpacing()
                && style.sideMargin == draft.sideMargin()
                && style.align == draft.align()
                && style.indent == draft.indent()
                && style.convert == draft.convert(), "copyInto: 8 个 style_* 字段写回");
        check(edits == 0, "copyInto 只改内存；落盘仍由调用方 style.save() 完成");

        StyleDraft after = StyleDraft.from(style, draft.paragraphReflow(), draft.fontSize());
        check(after.equals(draft), "copyInto 后的快照与草稿一致（equals 闭合）");

        draft.setFontSize(999);
        check(draft.fontSize() == StyleDraft.MIN_FONT_SIZE + 6, "字号非法值回退 20");
        draft.setFontSize(14);
        check(draft.fontSize() == 14, "字号下界 14");
        draft.setFontSize(32);
        check(draft.fontSize() == 32, "字号上界 32");

        System.out.println("PASS: StyleDraft probe (" + checks + " checks, edits=" + edits + ")");
    }

    private static int[] snapshot(ReaderStyle style) {
        return new int[]{style.stylePreset, style.lineSpacing, style.paraSpacing,
                style.letterSpacing, style.sideMargin, style.align, style.indent, style.convert};
    }

    private static boolean sameStyle(ReaderStyle style, int[] expected) {
        int[] now = snapshot(style);
        for (int i = 0; i < expected.length; i++) {
            if (now[i] != expected[i]) return false;
        }
        return true;
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError("FAIL: " + label);
        System.out.println("  ok  " + label);
    }

    private static SharedPreferences stubPrefs() {
        return (SharedPreferences) Proxy.newProxyInstance(
                StyleDraftProbe.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, methodArgs) -> {
                    String name = method.getName();
                    if ("getInt".equals(name)) return initialValue((String) methodArgs[0]);
                    if ("edit".equals(name)) {
                        edits++;
                        throw new AssertionError("草稿阶段/ copyInto 不得调用 prefs.edit()");
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
    }

    private static int initialValue(String key) {
        switch (key) {
            case "style_preset": return ReaderPresets.STANDARD;
            case "style_line": return 2;
            case "style_para": return 1;
            case "style_letter": return 0;
            case "style_margin": return 2;
            case "style_align": return ReaderStyle.ALIGN_LEFT;
            case "style_indent": return 0;
            case "style_convert": return ReaderStyle.CONVERT_OFF;
            default: return 0;
        }
    }
}
