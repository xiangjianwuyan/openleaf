package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-JVM classification for a dropped batch of files (design §3). Operates only on caller
 * supplied display names / MIME strings / header bytes: no file I/O, no Android Uri, no DB.
 * The Android layer resolves URIs, reads the first four bytes for {@link #isZipSignature} and
 * performs import or "open individually" dispatch.
 */
public final class DropBatch {
    public enum Kind { BOOK, COMIC, UNSUPPORTED }

    /** Matches MainActivity's existing ACTION_OPEN_DOCUMENT cap. */
    public static final int MAX_DROPPED_URIS = 20;

    private static final String BOOK_EXTS = "|txt|md|epub|pdf|";
    private static final String COMIC_EXTS = "|cbz|zip|cbr|rar|";
    private static final String BOOK_MIMES = "|text/plain|text/markdown|application/epub+zip|"
            + "application/pdf|application/x-pdf|";
    private static final String COMIC_MIMES = "|application/zip|application/x-zip-compressed|"
            + "application/x-cbz|application/vnd.comicbook+zip|";

    /** Extension wins; MIME is the fallback. Unknown / null inputs are UNSUPPORTED. */
    public static Kind classify(String displayName, String mime) {
        String ext = extension(displayName);
        if (!ext.isEmpty()) {
            if (BOOK_EXTS.contains("|" + ext + "|")) return Kind.BOOK;
            if (COMIC_EXTS.contains("|" + ext + "|")) return Kind.COMIC;
        }
        String m = normalizeMime(mime);
        if (!m.isEmpty()) {
            if (BOOK_MIMES.contains("|" + m + "|")) return Kind.BOOK;
            if (COMIC_MIMES.contains("|" + m + "|")) return Kind.COMIC;
        }
        return Kind.UNSUPPORTED;
    }

    /** True for the three ZIP local-header / empty-archive / spanned markers. */
    public static boolean isZipSignature(byte[] head) {
        if (head == null || head.length < 4) return false;
        return head[0] == 'P' && head[1] == 'K' && (
                (head[2] == 3 && head[3] == 4)
                        || (head[2] == 5 && head[3] == 6)
                        || (head[2] == 7 && head[3] == 8));
    }

    /** Per-item three-state wording from design §12; BOOK has no failure text. */
    public static String reason(Kind kind, String name) {
        String n = name == null ? "" : name;
        if (kind == Kind.COMIC) return n + "：未入库，可在漫画阅读器中逐个打开";
        if (kind == Kind.UNSUPPORTED) return n + "：仅支持 TXT/MD、EPUB、PDF；漫画请拖 CBZ/ZIP";
        return "";
    }

    /** Extra §12 failure strings, kept as pure functions for the Android layer to reuse. */
    public static String reasonInvalidZip(String name) {
        return (name == null ? "" : name) + "：不是有效的 ZIP/CBZ 文件";
    }

    /** Extra §12 failure string for an expired persistable URI permission. */
    public static String reasonPermission(String name) {
        return (name == null ? "" : name) + "：文件访问授权已失效，请重新拖入";
    }

    /** One rejected item plus its exact reason text. */
    public static final class Rejected {
        public final String name, reason;

        public Rejected(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }
    }

    /** Result of {@link #split(List, List)}; all lists are unmodifiable. */
    public static final class Batch {
        public final List<String> books;       // txt/md/epub/pdf — ready for the import queue
        public final List<String> comics;      // cbz/zip — open individually after signature check
        public final List<Rejected> rejected;  // unsupported items with wording
        public final List<String> duplicates;  // skipped because the display name repeated
        public final int overflow;             // items beyond MAX_DROPPED_URIS, not processed

        public Batch(List<String> books, List<String> comics, List<Rejected> rejected,
                     List<String> duplicates, int overflow) {
            this.books = Collections.unmodifiableList(new ArrayList<String>(books));
            this.comics = Collections.unmodifiableList(new ArrayList<String>(comics));
            this.rejected = Collections.unmodifiableList(new ArrayList<Rejected>(rejected));
            this.duplicates = Collections.unmodifiableList(new ArrayList<String>(duplicates));
            this.overflow = overflow;
        }
    }

    /**
     * Classify a whole dropped batch in one pass. At most {@link #MAX_DROPPED_URIS} items are
     * processed; the rest are counted in {@link Batch#overflow}. Duplicate display names
     * (case-insensitive, trimmed) are skipped and reported in {@link Batch#duplicates}.
     */
    public static Batch split(List<String> names, List<String> mimes) {
        List<String> books = new ArrayList<String>();
        List<String> comics = new ArrayList<String>();
        List<Rejected> rejected = new ArrayList<Rejected>();
        List<String> duplicates = new ArrayList<String>();
        if (names == null || names.isEmpty()) return new Batch(books, comics, rejected, duplicates, 0);

        int overflow = Math.max(0, names.size() - MAX_DROPPED_URIS);
        int count = Math.min(names.size(), MAX_DROPPED_URIS);
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            String name = names.get(i);
            String mime = (mimes != null && i < mimes.size()) ? mimes.get(i) : null;
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                duplicates.add(name);
                continue;
            }
            Kind kind = classify(name, mime);
            if (kind == Kind.BOOK) books.add(name);
            else if (kind == Kind.COMIC) comics.add(name);
            else rejected.add(new Rejected(name, reason(kind, name)));
        }
        return new Batch(books, comics, rejected, duplicates, overflow);
    }

    private static String extension(String name) {
        if (name == null) return "";
        String n = name.trim();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) return "";
        return n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeMime(String mime) {
        if (mime == null) return "";
        int semi = mime.indexOf(';');
        return (semi < 0 ? mime : mime.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    private DropBatch() { }
}
