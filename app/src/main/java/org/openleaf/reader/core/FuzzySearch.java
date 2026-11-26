package org.openleaf.reader.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地容错搜索：拼写错误（含相邻字符转置）也能召回。
 *
 * 设计借鉴 Shelf（MIT）的「trigram 倒排召回 + Damerau-Levenshtein 复核 + 稀疏门控」思路，
 * 由 OpenLeaf 自行实现，无外部依赖，可在纯 JVM 下测试。中文不依赖分词词表，
 * 用单字 + 相邻双字 bigram 归一化，错一字时仍有一半 bigram 命中。
 *
 * 用法：先 {@link #index(String, String...)} 建文档，再 {@link #search(String, int)} 查询。
 * 索引与查询都是内存结构；调用方负责在文档变化时重建。
 */
public final class FuzzySearch {
    private FuzzySearch() { }

    /** 单条文档：稳定 id + 可检索字段（标题权重 1.0，作者 0.85，系列 0.9）。 */
    public static final class Document {
        public final String id;
        public final String title;
        public final String author;
        public final String series;

        public Document(String id, String title, String author, String series) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("id 不能为空");
            this.id = id;
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.series = series == null ? "" : series;
        }
    }

    /** 排序方式：相关度（默认）/ 最新（按加入顺序倒序）。 */
    public enum Sort { RELEVANCE, NEWEST }

    /** 命中结果：文档 + 归一化相关度分数（0..1）。 */
    public static final class Hit {
        public final Document document;
        public final float score;
        Hit(Document document, float score) { this.document = document; this.score = score; }
    }

    private static final int MAX_TOKEN_LENGTH = 24;
    private static final int ALLOWED_1 = 6;   // token <=6 允许 1 个错字
    private static final int FUZZY_CANDIDATES = 60;
    private static final int RERANK_DEPTH = 250;
    private static final float ONE_TYPO = 0.72f;
    private static final float TWO_TYPOS = 0.5f;
    private static final float SPARSE_EXACT = 50f;
    private static final float SPARSE_COMPLETIONS = 20f;
    private static final float AUTHOR_FIELD = 0.85f;
    private static final float SERIES_FIELD = 0.9f;

    /** 索引：term → 文档权重映射；term → trigram 集合；文档有序列表。 */
    public static final class Index {
        private final Map<String, Map<String, Float>> postings = new HashMap<>();
        private final Map<String, Set<String>> grams = new HashMap<>();
        private final List<Document> documents = new ArrayList<>();
        private final Map<String, Document> byId = new HashMap<>();
        private final Set<String> allTerms = new HashSet<>();

        private Index() { }

        /** 返回文档数量。 */
        public int size() { return documents.size(); }
    }

    /**
     * 建立索引。为避免 Index 内部共享状态错误，这里用局部构建再填充的清晰实现。
     */
    public static Index index(List<Document> documents) {
        Index index = new Index();
        if (documents == null) return index;
        for (Document document : documents) {
            index.documents.add(document);
            index.byId.put(document.id, document);
            addField(index, document.title, document.id, 1.0f);
            addField(index, document.author, document.id, AUTHOR_FIELD);
            addField(index, document.series, document.id, SERIES_FIELD);
        }
        // 预计算每个 term 的 trigram（含边界哨兵），供模糊召回
        for (String term : index.allTerms) index.grams.put(term, trigrams(term));
        return index;
    }

    public static Index index(Document... documents) {
        return index(documents == null ? Collections.<Document>emptyList() : Arrays.asList(documents));
    }

    private static void addField(Index index, String text, String docId, float fieldWeight) {
        if (text == null || text.isEmpty()) return;
        for (String token : tokenize(text)) {
            if (token.isEmpty()) continue;
            index.allTerms.add(token);
            Map<String, Float> docWeights = index.postings.get(token);
            if (docWeights == null) { docWeights = new HashMap<>(); index.postings.put(token, docWeights); }
            float combined = fieldWeight;
            Float previous = docWeights.get(docId);
            if (previous != null) combined += previous;
            docWeights.put(docId, Math.min(2.0f, combined));
        }
    }

    /**
     * 归一化分词：西文折叠变音符/全角转半角/小写；CJK 输出单字+相邻双字 bigram。
     * 不依赖 ICU，仅用 JDK 自带 Normalizer，兼容 Android API 26+。
     */
    public static List<String> tokenize(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder word = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushWord(result, word);
                cjkRun.appendCodePoint(cp);
            } else {
                flushCjk(result, cjkRun);
                int folded = fold(cp);
                if (folded < 0) continue; // 撇号/组合音符等忽略
                if (folded == ' ' || Character.isWhitespace(folded)) { flushWord(result, word); continue; }
                char c = (char) folded;
                if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 'a');
                word.append(c);
                if (word.length() >= MAX_TOKEN_LENGTH) flushWord(result, word);
            }
        }
        flushWord(result, word);
        flushCjk(result, cjkRun);
        return result;
    }

    private static void flushWord(List<String> out, StringBuilder word) {
        if (word.length() >= 2) out.add(word.toString());
        word.setLength(0);
    }

    private static void flushCjk(List<String> out, StringBuilder run) {
        int length = run.length();
        for (int i = 0; i < length; i++) {
            out.add(String.valueOf(run.charAt(i)));
            if (i + 1 < length) out.add(run.substring(i, i + 2));
        }
        run.setLength(0);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30ff)   // 日文假名
                || (cp >= 0x3400 && cp <= 0x9fff)  // CJK 汉字
                || (cp >= 0xac00 && cp <= 0xd7af)  // 韩文 Hangul
                || (cp >= 0xf900 && cp <= 0xfaff); // 兼容汉字
    }

    /** 变音符折叠 + 全角转半角；返回 -1 表示忽略该字符。 */
    private static int fold(int cp) {
        if (cp >= 0xff01 && cp <= 0xff5e) return cp - 0xfee0;
        if (cp >= 0x0300 && cp <= 0x036f) return -1; // 组合音符
        if (cp == '\'' || cp == 0x2019) return -1;   // 撇号
        if (cp == 0x00df) return 's';                 // ß
        if (cp == 0x00f8) return 'o';                 // ø
        if (cp == 0x0111) return 'd';                 // đ
        if (cp == 0x0142) return 'l';                 // ł
        if (cp == 0x0451) return 0x0435;              // ё → е
        if (cp >= 0x00c0 && cp <= 0x00c5) return 'a';
        if (cp >= 0x00c8 && cp <= 0x00cb) return 'e';
        if (cp >= 0x00cc && cp <= 0x00cf) return 'i';
        if (cp >= 0x00d2 && cp <= 0x00d6) return 'o';
        if (cp >= 0x00d9 && cp <= 0x00dc) return 'u';
        if (cp >= 0x00e0 && cp <= 0x00e5) return 'a';
        if (cp >= 0x00e8 && cp <= 0x00eb) return 'e';
        if (cp >= 0x00ec && cp <= 0x00ef) return 'i';
        if (cp >= 0x00f2 && cp <= 0x00f6) return 'o';
        if (cp >= 0x00f9 && cp <= 0x00fc) return 'u';
        if (cp >= 0x00fd && cp <= 0x00ff) return 'y';
        // 标点/符号统一视为分隔符（CJK 已在 isCjk 分支处理）
        if (!Character.isLetterOrDigit(cp)) return ' ';
        return cp;
    }

    /** 带边界哨兵的 trigram，让短词也有位置特征。 */
    static Set<String> trigrams(String term) {
        Set<String> result = new HashSet<>();
        String padded = "$$" + term + "$";
        for (int i = 0; i + 3 <= padded.length(); i++) result.add(padded.substring(i, i + 3));
        return result;
    }

    /** 受限 Damerau-Levenshtein 距离（支持相邻字符转置），带 max 早停。 */
    static int editDistance(String a, String b, int max) {
        if (a.equals(b)) return 0;
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            int rowMin = d[i][0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(d[i - 1][j] + 1, Math.min(d[i][j - 1] + 1, d[i - 1][j - 1] + cost));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
                if (d[i][j] < rowMin) rowMin = d[i][j];
            }
            if (rowMin > max) return max + 1;
        }
        return d[n][m];
    }

    /** 查询。精确命中优先；稀疏查询才启用模糊召回；两段式排序截断。 */
    public static List<Hit> search(Index index, String query, int limit) {
        return search(index, query, limit, Sort.RELEVANCE);
    }

    public static List<Hit> search(Index index, String query, int limit, Sort sort) {
        List<Hit> empty = new ArrayList<>();
        if (index == null || query == null || query.trim().isEmpty() || limit <= 0) return empty;
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return empty;

        // 第一段：精确/前缀召回，同时累计每篇文档的相关度
        Map<String, Float> scores = new HashMap<>();
        Map<String, Integer> matched = new HashMap<>();
        for (String token : tokens) {
            float exactWeight = exactWeight(index, token);
            for (Map.Entry<String, Map<String, Float>> entry : index.postings.entrySet()) {
                String term = entry.getKey();
                float relation = matchRelation(token, term);
                if (relation <= 0f) continue;
                for (Map.Entry<String, Float> posting : entry.getValue().entrySet()) {
                    String docId = posting.getKey();
                    float contribution = relation * posting.getValue();
                    scores.put(docId, (scores.containsKey(docId) ? scores.get(docId) : 0f) + contribution);
                    if (relation >= 0.9f) matched.put(docId, (matched.containsKey(docId) ? matched.get(docId) : 0) + 1);
                }
            }
        }

        // 稀疏门控：精确词频低 + 前缀补全少 → 启用容错召回
        boolean sparse = false;
        for (String token : tokens) {
            float exact = exactWeight(index, token);
            float completions = completionWeight(index, token);
            if (exact < SPARSE_EXACT && (completions < SPARSE_COMPLETIONS || exact == 0f)) { sparse = true; break; }
        }
        if (sparse) {
            for (String token : tokens) {
                int typoLimit = allowedTypos(token);
                if (typoLimit == 0) continue;
                for (String candidate : fuzzyTerms(index, token, typoLimit)) {
                    Map<String, Float> posting = index.postings.get(candidate);
                    if (posting == null) continue;
                    float relation = oneTypoWeight(token, candidate, typoLimit);
                    if (relation <= 0f) continue;
                    for (Map.Entry<String, Float> hit : posting.entrySet()) {
                        String docId = hit.getKey();
                        scores.put(docId, (scores.containsKey(docId) ? scores.get(docId) : 0f) + relation * hit.getValue());
                        // 容错命中也计入覆盖率，否则会被 cover==0 过滤掉
                        matched.put(docId, (matched.containsKey(docId) ? matched.get(docId) : 0) + 1);
                    }
                }
            }
        }

        // 覆盖率过滤 + 归一化 + 第二段排序截断
        float idf = (float) Math.log((index.size() + 1.0) / (Math.min(index.documents.size(), Math.max(1, tokens.size())) + 1.0)) + 1f;
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Float> entry : scores.entrySet()) {
            int cover = matched.containsKey(entry.getKey()) ? matched.get(entry.getKey()) : 0;
            if (cover == 0) continue;
            float coverage = (float) cover / tokens.size();
            float score = entry.getValue() / Math.max(1f, idf * tokens.size()) * coverage * coverage;
            if (score <= 0f) continue;
            Document document = findDocument(index, entry.getKey());
            if (document != null) hits.add(new Hit(document, Math.min(1f, score)));
        }
        // 共享文档顺序映射：避免 RELEVANCE 每次 indexOf 的 O(N)，NEWEST 复用同一份
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < index.documents.size(); i++) order.put(index.documents.get(i).id, i);
        hits.sort(Comparator.comparingDouble((Hit h) -> h.score).reversed()
                .thenComparingInt(h -> order.getOrDefault(h.document.id, Integer.MAX_VALUE)));
        if (hits.size() > RERANK_DEPTH) hits = new ArrayList<>(hits.subList(0, RERANK_DEPTH));

        if (sort == Sort.NEWEST) {
            hits.sort(Comparator.comparingInt((Hit h) -> order.getOrDefault(h.document.id, 0)).reversed());
        }
        if (hits.size() > limit) hits = new ArrayList<>(hits.subList(0, limit));
        return hits;
    }

    private static Document findDocument(Index index, String id) {
        return index.byId.get(id);
    }

    private static float exactWeight(Index index, String token) {
        Map<String, Float> posting = index.postings.get(token);
        if (posting == null) return 0f;
        float sum = 0f;
        for (float weight : posting.values()) sum += weight;
       return sum;
    }

    private static float completionWeight(Index index, String token) {
        float sum = 0f;
        for (Map.Entry<String, Map<String, Float>> entry : index.postings.entrySet()) {
            if (!entry.getKey().startsWith(token)) continue;
            for (float weight : entry.getValue().values()) sum += weight;
        }
        return sum;
    }

    /** 精确 1.0 / 长前缀 0.9 / 短前缀 0.75；CJK 单字与 bigram 也走前缀。 */
    private static float matchRelation(String query, String term) {
        if (query.equals(term)) return 1.0f;
        if (term.startsWith(query)) return query.length() >= 4 ? 0.9f : 0.75f;
        return 0f;
    }

    private static int allowedTypos(String token) {
        int length = token.length();
        return length <= 2 ? 0 : length <= ALLOWED_1 ? 1 : 2;
    }

    private static float oneTypoWeight(String query, String candidate, int limit) {
        int distance = editDistance(query, candidate, limit);
        if (distance == 1) return ONE_TYPO;
        if (distance == 2) return TWO_TYPOS;
        return 0f;
    }

    /**
     * 候选召回：先用 trigram 命中数做粗筛（相对 token 变体），再编辑距离复核。
     * 本地书架词表规模有限，粗筛仅用于排序限量；编辑距离是最终裁决，保证不漏召回。
     */
    private static List<String> fuzzyTerms(Index index, String token, int typoLimit) {
        // trigram 命中数作为优先级提示：公共 trigram 越多越可能是想找的词
        Map<String, Integer> counts = new HashMap<>();
        Set<String> queryGrams = trigrams(token);
        for (String term : index.allTerms) {
            if (term.length() < 2 || Math.abs(term.length() - token.length()) > typoLimit) continue;
            Set<String> termGrams = index.grams.get(term);
            if (termGrams == null) continue;
            int shared = 0;
            for (String gram : queryGrams) if (termGrams.contains(gram)) shared++;
            counts.put(term, shared);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            if (result.size() >= FUZZY_CANDIDATES) break;
            int distance = editDistance(token, entry.getKey(), typoLimit);
            if (distance >= 1 && distance <= typoLimit) result.add(entry.getKey());
        }
        return result;
    }
}
