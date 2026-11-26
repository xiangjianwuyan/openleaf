import org.openleaf.reader.core.TextChapterHeadings;

/** No Android or third-party dependencies. Exercises discovery, not book-order reconstruction. */
public final class TextChapterHeadingsTest {
    private static int checks;

    private static void expect(boolean expected, boolean isolated, String... lines) {
        for (String line : lines) {
            boolean actual = TextChapterHeadings.isHeading(line, isolated);
            if (actual != expected) throw new AssertionError("Unexpected classification for " + line
                    + " (isolated=" + isolated + "): " + actual);
            checks++;
        }
    }

    public static void main(String[] args) {
        expect(true, false, "第１２章　月下归来", "第十二节", "第兩百零一回：远方的风", "第四卷：启程",
                "第廿三章　再会", "第壹佰貳拾章 星夜", "章3 · 小镇", "【第一话】远方", "【第一话】 远方", "【第一话】",
                "[第３章]：秘密", "（第二幕：重逢）", "第３話 闇の入口", "巻三 出発", "제 2장 돌아온 밤",
                "Chapter 10 — Next Steps", "CHAPTER V: FIRST LIGHT", "Chapter Two", "Chapter twenty-one — Home",
                "Part II", "Section 1.2: Methods", "Episode #03: April", "VOL. 7", "Act 3.", "Appendix A",
                "Prologue", "尾聲", "番外二", "後記", "附录", "\uFEFF  第一章　夜风\t",
                "# 一个无数字的 Markdown 章名", "### Chapter Ends ###", "## 开篇", "###### 尾注");
        expect(true, true, "第十二章归来", "序章：夜航", "后记 一封信", "Epilogue — Goodbye",
                "01. 初识", "1.2.3 内容", "００１ 启程", "二十、故事", "IV. Home");
        expect(false, false, "我们讨论第一章的内容。", "第一章讲述了雨夜里的故事", "第十二章归来",
                "1. 温习一遍", "IV. Home", "2.3 方法", "001 初识", "1、条目", "第一章。", "序章 一封信",
                "第一章\n第二章", "#标签", "# ###", "###", "###   ", "##no-space", "####### 七个井号");
        expect(false, true, "", "   \t　", null, "第八章的内容", "第八章中提到作者的源流", "第八章讲述了迷失的经过",
                "序章的内容是这样", "Chapter 4 introduces new material.", "Chapter Civil", "ChapterXylophone", "chocolate II",
                "1.2.3", "1. 20", "12:30 开会", "1. https://example.invalid/path", "1. 接下来继续阅读。", "没有结构记号的短句");
        expect(true, false, "卷一", "章一 风起", "Chapter xiv: Home", "Volume XXI");
        expect(true, true, "【雨夜归来】");
        expect(false, false, "【雨夜归来】", "第一章：", "Chapter IV —");
        expect(false, true, "第十二章已经读完了", "第十二章 已经读完了", "第十二章 新旅程，",
                "【重要提示】", "【目录】", "【夜雨，】", "IIX. Invalid", "Episode IIX");
        expect(false, false, "# " + new String(new char[520]).replace('\0', '长'));
        System.out.println("PASS: " + checks + " text chapter heading cases");
    }
}
