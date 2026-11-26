package org.openleaf.reader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM, local-only book recommendation (design §2). No Android, DB, SharedPreferences or
 * network access: every input is supplied by the caller and {@link #rank} is a deterministic
 * function of its arguments. Callers assemble signals from ReadingStatsStore / BookStore /
 * SharedPreferences("reader").opened_&lt;bookId&gt; and pass them here.
 */
public final class Recommendation {
    public static final int DEFAULT_LIMIT = 5;
    public static final long DEFAULT_MIN_SECONDS = 60;
    public static final int DEFAULT_MAX_AGE_DAYS = 180;

    private static final double READ_PENALTY = 0.35;
    private static final double MS_PER_DAY = 86_400_000.0;

    /** One read book supplied by the caller. Tags are copied defensively. */
    public static final class Signal {
        public final String bookId;
        public final List<String> tags;
        public final long seconds, lastOpenedMs;

        public Signal(String bookId, List<String> tags, long seconds, long lastOpenedMs) {
            this.bookId = bookId;
            this.tags = tags == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(tags));
            this.seconds = seconds;
            this.lastOpenedMs = lastOpenedMs;
        }
    }

    /** One suggestible book supplied by the caller. Tags are copied defensively. */
    public static final class Candidate {
        public final String bookId, title;
        public final List<String> tags;

        public Candidate(String bookId, String title, List<String> tags) {
            this.bookId = bookId;
            this.title = title;
            this.tags = tags == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(tags));
        }
    }

    /** An explainable suggestion; reason uses the exact wording contract from design §12. */
    public static final class Suggestion {
        public final String bookId, title, reason;
        public final float score;

        public Suggestion(String bookId, String title, String reason, float score) {
            this.bookId = bookId;
            this.title = title;
            this.reason = reason;
            this.score = score;
        }
    }

    /**
     * Rank candidates against a local reading profile.
     *
     * <ol>
     *   <li>weight(book) = log1p(seconds/60) * max(0.1, 1 - ageDays/180); books under
     *       60 s or with lastOpenedMs == 0 are ignored.</li>
     *   <li>profile[tag] += weight(book), duplicate tags inside one book counted once.</li>
     *   <li>score(c) = sum(profile[t] * log(1 + N/df(t))) over c.tags, then * 0.35 if the
     *       candidate was already read (down-rank, never exclude, to allow re-reading).</li>
     *   <li>score desc, then title asc, then bookId asc; score &lt;= 0 omitted; top {@code limit}.</li>
     * </ol>
     *
     * Signals are processed in a canonical order (bookId/lastOpenedMs/seconds/tags) so the
     * result does not depend on caller-supplied list order, including floating-point sums.
     */
    public static List<Suggestion> rank(List<Signal> signals, List<Candidate> candidates,
                                        Set<String> excludeBookIds, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) return Collections.emptyList();

        final long now = System.currentTimeMillis();
        List<Signal> usable = new ArrayList<Signal>();
        if (signals != null) {
            for (Signal s : signals) {
                if (s == null || s.bookId == null) continue;
                if (s.seconds < DEFAULT_MIN_SECONDS || s.lastOpenedMs <= 0) continue;
                usable.add(s);
            }
        }
        if (usable.isEmpty()) return Collections.emptyList();
        Collections.sort(usable, new Comparator<Signal>() {
            @Override public int compare(Signal a, Signal b) {
                int c = a.bookId.compareTo(b.bookId);
                if (c != 0) return c;
                c = Long.compare(a.lastOpenedMs, b.lastOpenedMs);
                if (c != 0) return c;
                c = Long.compare(a.seconds, b.seconds);
                if (c != 0) return c;
                return a.tags.toString().compareTo(b.tags.toString());
            }
        });

        Set<String> readIds = new HashSet<String>();
        Map<String, Double> profile = new LinkedHashMap<String, Double>();
        Map<String, Double> tagTopWeight = new HashMap<String, Double>();
        Map<String, String> tagTopBook = new HashMap<String, String>();
        for (Signal s : usable) {
            readIds.add(s.bookId);
            double ageDays = (now - s.lastOpenedMs) / MS_PER_DAY;
            if (ageDays < 0) ageDays = 0;
            double recency = Math.max(0.1, 1 - ageDays / DEFAULT_MAX_AGE_DAYS);
            double weight = Math.log1p(s.seconds / 60.0) * recency;
            for (String t : distinct(s.tags)) {
                Double prev = profile.get(t);
                profile.put(t, (prev == null ? 0.0 : prev) + weight);
                Double best = tagTopWeight.get(t);
                if (best == null || weight > best
                        || (weight == best && s.bookId.compareTo(tagTopBook.get(t)) < 0)) {
                    tagTopWeight.put(t, weight);
                    tagTopBook.put(t, s.bookId);
                }
            }
        }
        if (profile.isEmpty()) return Collections.emptyList();

        int n = candidates.size();
        Map<String, Integer> df = new HashMap<String, Integer>();
        Map<String, String> idToTitle = new HashMap<String, String>();
        for (Candidate c : candidates) {
            if (c == null) continue;
            if (c.bookId != null && !idToTitle.containsKey(c.bookId)) idToTitle.put(c.bookId, c.title);
            for (String t : distinct(c.tags)) {
                Integer prev = df.get(t);
                df.put(t, (prev == null ? 0 : prev) + 1);
            }
        }

        Set<String> excluded = excludeBookIds == null ? Collections.<String>emptySet() : excludeBookIds;
        List<Suggestion> out = new ArrayList<Suggestion>();
        for (Candidate c : candidates) {
            if (c == null || c.bookId == null || excluded.contains(c.bookId)) continue;
            double score = 0.0;
            String topTag = null;
            double topContribution = Double.NEGATIVE_INFINITY;
            for (String t : distinct(c.tags)) {
                Double pw = profile.get(t);
                if (pw == null) continue;
                Integer docs = df.get(t);
                int d = docs == null ? 0 : docs;
                if (d <= 0) continue;
                double idf = Math.log(1 + (double) n / d);
                double contribution = pw * idf;
                score += contribution;
                if (contribution > topContribution) {
                    topContribution = contribution;
                    topTag = t;
                }
            }
            if (score <= 0) continue;
            if (readIds.contains(c.bookId)) score *= READ_PENALTY;
            String readTitle = idToTitle.get(tagTopBook.get(topTag));
            if (readTitle == null || readTitle.isEmpty()) readTitle = tagTopBook.get(topTag);
            String reason = topTag == null ? "" : "因为你读过《" + readTitle + "》· 同标签「" + topTag + "」";
            out.add(new Suggestion(c.bookId, c.title, reason, (float) score));
        }

        Collections.sort(out, new Comparator<Suggestion>() {
            @Override public int compare(Suggestion a, Suggestion b) {
                if (a.score != b.score) return a.score > b.score ? -1 : 1;
                int c = compareNullable(a.title, b.title);
                if (c != 0) return c;
                return compareNullable(a.bookId, b.bookId);
            }
        });
        return out.size() <= limit ? out : new ArrayList<Suggestion>(out.subList(0, limit));
    }

    private static Set<String> distinct(List<String> tags) {
        Set<String> result = new LinkedHashSet<String>();
        for (String t : tags) if (t != null && !t.isEmpty()) result.add(t);
        return result;
    }

    private static int compareNullable(String a, String b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    private Recommendation() { }
}
