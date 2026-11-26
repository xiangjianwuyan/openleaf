package org.openleaf.reader;

import java.util.HashMap;
import java.util.Map;

/**
 * 简繁单字转换。算法借鉴 foliate-js (MIT) 的查表替换思路，由 OpenLeaf 自行实现。
 * 只转换 CJK 基本区字符，未收录字原样保留；映射表见 ChineseTable。
 */
final class ChineseConverter {
    private static final Map<Character, Character> S2T = new HashMap<>();
    private static final Map<Character, Character> T2S = new HashMap<>();

    static {
        String s = ChineseTable.SIMPLIFIED;
        String t = ChineseTable.TRADITIONAL;
        int n = Math.min(s.length(), t.length());
        for (int i = 0; i < n; i++) {
            char sc = s.charAt(i), tc = t.charAt(i);
            if (!S2T.containsKey(sc)) S2T.put(sc, tc);
            if (!T2S.containsKey(tc)) T2S.put(tc, sc);
        }
    }

    private ChineseConverter() {}

    /** mode: ReaderStyle.CONVERT_OFF / CONVERT_S2T / CONVERT_T2S */
    static String convert(String text, int mode) {
        if (text == null || text.isEmpty() || mode == ReaderStyle.CONVERT_OFF) return text;
        Map<Character, Character> map = mode == ReaderStyle.CONVERT_S2T ? S2T : T2S;
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character converted = map.get(c);
            result.append(converted != null ? converted : c);
        }
        return result.toString();
    }
}
