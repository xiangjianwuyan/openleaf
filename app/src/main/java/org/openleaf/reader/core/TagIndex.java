package org.openleaf.reader.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** BookStore.allTags() → 标签聚合（同书去重 / NFKC 合并 / 稳定排序）。纯 JVM，无 Android 类型。 */
public final class TagIndex {
    private TagIndex() { }

    public static final class Entry {
        public final String name;
        public final String key;
        public final int books;

        Entry(String name, String key, int books) {
            this.name = name;
            this.key = key;
            this.books = books;
        }
    }

    /** 输入即 BookStore.allTags() 的返回值；输出按 (books desc, name asc) 排序。 */
    public static List<Entry> summarize(Map<String, List<String>> perBookTags) {
        Map<String, Bucket> byKey = new HashMap<>();
        if (perBookTags != null) {
            for (Map.Entry<String, List<String>> book : perBookTags.entrySet()) {
                List<String> names = book.getValue();
                if (names == null) continue;
                Set<String> seen = new HashSet<>(); // 同书同 key 只计一次
                for (String name : names) {
                    if (name == null) continue;
                    String key = key(name);
                    if (!seen.add(key)) continue;
                    Bucket bucket = byKey.get(key);
                    if (bucket == null) { bucket = new Bucket(); byKey.put(key, bucket); }
                    bucket.add(name);
                }
            }
        }
        List<Entry> out = new ArrayList<>(byKey.size());
        for (Map.Entry<String, Bucket> e : byKey.entrySet()) {
            out.add(new Entry(e.getValue().best(), e.getKey(), e.getValue().books));
        }
        out.sort(Comparator.comparingInt((Entry e) -> e.books).reversed().thenComparing(e -> e.name));
        return out;
    }

    /** key = NFKC(name).toLowerCase(Locale.ROOT)（与 BookStore.normalizeTag 的 tag_key 同构）。 */
    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static final class Bucket {
        int books;
        final Map<String, Integer> spellings = new HashMap<>();

        void add(String name) {
            books++;
            spellings.merge(name, 1, Integer::sum);
        }

        /** 显示出现次数最多的写法；并列取 String.compareTo 最小者 → 结果与输入顺序无关。 */
        String best() {
            String best = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> e : spellings.entrySet()) {
                int count = e.getValue();
                if (count > bestCount
                        || (count == bestCount && best != null && e.getKey().compareTo(best) < 0)) {
                    best = e.getKey();
                    bestCount = count;
                }
            }
            return best;
        }
    }
}
