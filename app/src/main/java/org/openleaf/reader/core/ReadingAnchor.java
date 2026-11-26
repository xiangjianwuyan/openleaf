package org.openleaf.reader.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Immutable, format-aware reading position. Percentages are never used as identity. */
public final class ReadingAnchor {
    public static final int VERSION = 1;
    public enum Kind { TEXT, EPUB }

    public final Kind kind;
    public final String chapterKey;
    public final int chapterOrdinal;
    public final int spineIndex;
    public final long sourceCharOffset;
    public final int chapterCharOffset;
    public final boolean atEnd;
    public final float lineFraction;
    public final int scrollY;

    private ReadingAnchor(Kind kind, String chapterKey, int chapterOrdinal, int spineIndex,
                          long sourceCharOffset, int chapterCharOffset, boolean atEnd,
                          float lineFraction, int scrollY) {
        this.kind = kind;
        this.chapterKey = chapterKey == null ? "" : chapterKey;
        this.chapterOrdinal = chapterOrdinal;
        this.spineIndex = spineIndex;
        this.sourceCharOffset = sourceCharOffset;
        this.chapterCharOffset = chapterCharOffset;
        this.atEnd = atEnd;
        this.lineFraction = lineFraction;
        this.scrollY = scrollY;
    }

    public static ReadingAnchor text(String chapterKey, int ordinal, int spineIndex,
                                     long sourceCharOffset, int chapterCharOffset,
                                     boolean atEnd, float lineFraction) {
        return new ReadingAnchor(Kind.TEXT, chapterKey, ordinal, spineIndex, sourceCharOffset,
                chapterCharOffset, atEnd, lineFraction, 0);
    }

    public static ReadingAnchor epub(String chapterKey, int ordinal, int spineIndex,
                                     int scrollY, float lineFraction) {
        return new ReadingAnchor(Kind.EPUB, chapterKey, ordinal, spineIndex, -1, 0,
                false, lineFraction, Math.max(0, scrollY));
    }

    public String serialize() {
        return VERSION + "|" + kind.name() + "|" + encode(chapterKey) + "|" + chapterOrdinal + "|"
                + spineIndex + "|" + sourceCharOffset + "|" + chapterCharOffset + "|"
                + (atEnd ? "1" : "0") + "|" + lineFraction + "|" + scrollY;
    }

    public static ReadingAnchor parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 10) return null;
        try {
            if (Integer.parseInt(parts[0]) != VERSION) return null;
            Kind kind = Kind.valueOf(parts[1].toUpperCase(Locale.ROOT));
            String key = decode(parts[2]);
            int ordinal = Integer.parseInt(parts[3]);
            int spine = Integer.parseInt(parts[4]);
            long source = Long.parseLong(parts[5]);
            int offset = Integer.parseInt(parts[6]);
            boolean end = "1".equals(parts[7]);
            float fraction = Float.parseFloat(parts[8]);
            int scroll = Integer.parseInt(parts[9]);
            return new ReadingAnchor(kind, key, ordinal, spine, source, offset, end, fraction, scroll);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
