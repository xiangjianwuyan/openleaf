package org.openleaf.reader;

import java.io.IOException;

/** Shared validation for saved and exchanged reviews; supplementary characters count once. */
final class BookReview {
    static final int MIN = 200, MAX = 300;
    private BookReview() {}
    static String validate(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        int count = value.codePointCount(0, value.length());
        if (count != 0 && (count < MIN || count > MAX))
            throw new IOException("书评请填写 200–300 字，或留空清除（当前 " + count + " 字）");
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (Character.getType(point) == Character.SURROGATE || Character.isISOControl(point)
                    && point != '\n' && point != '\r' && point != '\t')
                throw new IOException("书评包含不支持的字符");
            offset += Character.charCount(point);
        }
        return value;
    }
}
