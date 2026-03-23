package be.zvz.sony.launchersearchenhancer.search;

import android.text.TextUtils;

import java.util.Set;

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

    public static final int SCORE_PKG_EXACT = 620;
    public static final int SCORE_PKG_PREFIX = 490;
    public static final int SCORE_PKG_CONTAINS = 330;

    private static final Set<String> PACKAGE_STOPWORDS = Set.of(
            "com", "org", "net", "android", "launcher", "mobile", "app", "apps",
            "service", "services", "client", "global", "prod", "release", "debug"
    );

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
        best = Math.max(best, scorePackage(q, f.pkgNorm()));
        return best;
    }

    public static int scoreBasic(String q, String target, ScoreTier tier) {
        if (TextUtils.isEmpty(target)) return 0;
        if (target.equals(q)) return tier.exact;
        if (target.startsWith(q)) return tier.prefix;
        if (matchesWordPrefix(q, target)) return tier.wordPrefix;
        if (q.length() >= 2 && target.contains(q)) return tier.contains;
        return 0;
    }

    public static int scorePackage(String q, String pkgNorm) {
        if (TextUtils.isEmpty(pkgNorm) || q.length() < 2) return 0;
        int best = 0;
        for (String t : pkgNorm.split("[._\\-]+")) {
            if (TextUtils.isEmpty(t) || t.length() < 2 || PACKAGE_STOPWORDS.contains(t)) continue;
            if (t.equals(q)) best = Math.max(best, SCORE_PKG_EXACT);
            else if (t.startsWith(q)) best = Math.max(best, SCORE_PKG_PREFIX);
            else if (q.length() >= 3 && t.contains(q)) best = Math.max(best, SCORE_PKG_CONTAINS);
        }
        return best;
    }

    private static boolean matchesWordPrefix(String q, String text) {
        for (String w : text.split("[\\s\\-_.()\\[\\]/・]+")) {
            if (w.startsWith(q)) return true;
        }
        return false;
    }
}
