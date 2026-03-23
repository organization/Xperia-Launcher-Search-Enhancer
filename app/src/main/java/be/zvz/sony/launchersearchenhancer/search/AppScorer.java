package be.zvz.sony.launchersearchenhancer.search;

import android.text.TextUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import be.zvz.sony.launchersearchenhancer.record.AppForms;

public final class AppScorer {

    private AppScorer() {}

    public record ScoreTier(int exact, int prefix, int wordPrefix, int contains) {}

    public static final ScoreTier TITLE = new ScoreTier(1300, 1120, 980, 760);
    public static final ScoreTier KANA = new ScoreTier(1240, 1080, 960, 780);
    public static final ScoreTier KANA_LOOSE = new ScoreTier(1200, 1040, 930, 780);
    public static final ScoreTier LATIN = new ScoreTier(1020, 940, 860, 690);
    public static final ScoreTier CHOSEONG = new ScoreTier(1080, 960, 880, 740);
    public static final ScoreTier JAMO = new ScoreTier(980, 900, 830, 700);
    private static final int FUZZY_MAX = 500;

    private static final int SCORE_PKG_EXACT = 620;
    private static final int SCORE_PKG_PREFIX = 490;
    private static final int SCORE_PKG_CONTAINS = 330;

    private static final Set<String> PACKAGE_STOPWORDS = Set.of(
            "com", "org", "net", "android", "launcher", "mobile", "app", "apps",
            "service", "services", "client", "global", "prod", "release", "debug"
    );

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s\\-_.()\\[\\]/・]+");
    public static final Pattern PKG_SPLIT = Pattern.compile("[._\\-]+");

    public static int scoreWithForms(String q, AppForms f) {
        if (TextUtils.isEmpty(q)) return 0;
        int best = 0;
        best = Math.max(best, scoreBasic(q, f.titleNorm(), TITLE));
        best = Math.max(best, scoreBasic(q, f.titleHira(), KANA));
        best = Math.max(best, scoreBasic(q, f.titleKata(), KANA));
        best = Math.max(best, scoreBasic(q, f.titleKanaLoose(), KANA_LOOSE));
        best = Math.max(best, scoreBasic(q, f.titleLatin(), LATIN));
        best = Math.max(best, scoreBasic(q, f.titleCho(), CHOSEONG));
        best = Math.max(best, scoreBasic(q, f.titleJamo(), JAMO));
        best = Math.max(best, scorePackage(q, f.pkgTokens()));

        if (best == 0 && q.length() >= 3) {
            best = Math.max(best, scoreFuzzy(q, f.titleNorm(), FUZZY_MAX));
        }
        return best;
    }

    static int scoreBasic(String q, String target, ScoreTier tier) {
        if (TextUtils.isEmpty(target)) return 0;
        if (target.equals(q)) return tier.exact;
        if (target.startsWith(q)) return tier.prefix;
        if (matchesWordPrefix(q, target)) return tier.wordPrefix;
        if (q.length() >= 2 && target.contains(q)) return tier.contains;
        return 0;
    }

    static int scorePackage(String q, List<String> pkgTokens) {
        if (pkgTokens == null || pkgTokens.isEmpty() || q.length() < 2) return 0;
        int best = 0;
        for (String t : pkgTokens) {
            if (t.length() < 2 || PACKAGE_STOPWORDS.contains(t)) continue;
            if (t.equals(q)) best = Math.max(best, SCORE_PKG_EXACT);
            else if (t.startsWith(q)) best = Math.max(best, SCORE_PKG_PREFIX);
            else if (q.length() >= 3 && t.contains(q)) best = Math.max(best, SCORE_PKG_CONTAINS);
        }
        return best;
    }

    static int scoreFuzzy(String q, String target, int maxScore) {
        if (TextUtils.isEmpty(target) || TextUtils.isEmpty(q)) return 0;
        int maxDist = q.length() >= 5 ? 2 : 1;
        int dist = boundedLevenshtein(q, target, maxDist);
        if (dist < 0 || dist == 0) return 0; // dist==0 means exact match, already handled
        return (int) (maxScore * (1.0 - (double) dist / maxDist) * 0.6);
    }

    private static int boundedLevenshtein(String q, String target, int maxDist) {
        // Check if q matches any word in target within edit distance
        for (String word : WORD_SPLIT.split(target)) {
            if (word.isEmpty()) continue;
            int d = editDistance(q, word, maxDist);
            if (d >= 0 && d <= maxDist) return d;
        }
        // Also check against full target for short targets
        if (target.length() <= q.length() + maxDist) {
            int d = editDistance(q, target, maxDist);
            if (d >= 0 && d <= maxDist) return d;
        }
        return -1;
    }

    private static int editDistance(String a, String b, int maxDist) {
        int m = a.length(), n = b.length();
        if (Math.abs(m - n) > maxDist) return -1;

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > maxDist) return -1;
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n] <= maxDist ? prev[n] : -1;
    }

    private static boolean matchesWordPrefix(String q, String text) {
        for (String w : WORD_SPLIT.split(text)) {
            if (w.startsWith(q)) return true;
        }
        return false;
    }
}
