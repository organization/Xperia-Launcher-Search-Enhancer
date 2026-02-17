package be.zvz.sony.launchersearchenhancer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class MainModule extends XposedModule {

    private static final String TARGET_PACKAGE = "com.sonymobile.launcher";
    private static final String CLASS_DEFAULT_SEARCH_ALGO = "com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm";
    private static final String CLASS_GEHIDE_APPS = "com.sonymobile.launcher.gameenhancer.GeHideAppsList";
    private static final String CLASS_APPINFO = "com.android.launcher3.model.data.AppInfo";
    private static final String CLASS_ADAPTER_ITEM = "com.android.launcher3.allapps.BaseAllAppsAdapter$AdapterItem";
    private static final String CLASS_HOTSEAT_QSB = "com.android.searchlauncher.HotseatQsbWidget";

    private static final int MAX_RESULTS = 5;

    private static final int SCORE_TITLE_EXACT = 1300;
    private static final int SCORE_TITLE_PREFIX = 1120;
    private static final int SCORE_TITLE_WORD_PREFIX = 980;
    private static final int SCORE_TITLE_CONTAINS = 760;

    private static final int SCORE_KANA_EXACT = 1240;
    private static final int SCORE_KANA_PREFIX = 1080;
    private static final int SCORE_KANA_WORD_PREFIX = 960;
    private static final int SCORE_KANA_CONTAINS = 780;

    private static final int SCORE_LATIN_EXACT = 1020;
    private static final int SCORE_LATIN_PREFIX = 940;
    private static final int SCORE_LATIN_WORD_PREFIX = 860;
    private static final int SCORE_LATIN_CONTAINS = 690;

    private static final int SCORE_CHOSEONG_EXACT = 1080;
    private static final int SCORE_CHOSEONG_PREFIX = 960;
    private static final int SCORE_CHOSEONG_WORD_PREFIX = 880;
    private static final int SCORE_CHOSEONG_CONTAINS = 740;

    private static final int SCORE_JAMO_EXACT = 980;
    private static final int SCORE_JAMO_PREFIX = 900;
    private static final int SCORE_JAMO_WORD_PREFIX = 830;
    private static final int SCORE_JAMO_CONTAINS = 700;

    private static final int SCORE_PKG_EXACT = 620;
    private static final int SCORE_PKG_PREFIX = 490;
    private static final int SCORE_PKG_CONTAINS = 330;

    private static XposedModule module;

    private static Method sAsAppMethod;
    private static Method sGetGeHideAppsMethod;
    private static Field sTitleField;
    private static Field sComponentNameField;
    private static Method sGetPackageNameMethod;
    private static Field sFallbackSearchViewField;

    private static final ConcurrentHashMap<String, List<String>> sQueryConversions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> sIcuMap = new ConcurrentHashMap<>();

    private static final Set<String> PACKAGE_STOPWORDS = new HashSet<>(Arrays.asList(
            "com", "org", "net", "android", "launcher", "mobile", "app", "apps",
            "service", "services", "client", "global", "prod", "release", "debug"
    ));

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    public MainModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
        log("Init module");
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader cl = param.getClassLoader();

            Class<?> searchAlgoClass = cl.loadClass(CLASS_DEFAULT_SEARCH_ALGO);
            Method getTitleMatchResult = findMethod(searchAlgoClass, "getTitleMatchResult",
                    Context.class, List.class, String.class);
            hook(getTitleMatchResult, SearchAlgorithmHooker.class);

            try {
                Class<?> searchCallbackClass = cl.loadClass("com.android.launcher3.search.SearchCallback");
                Method doSearchWithConversions = findMethod(searchAlgoClass, "doSearch",
                        String.class, String[].class, searchCallbackClass);
                hook(doSearchWithConversions, SearchConversionsHooker.class);
            } catch (Throwable ignored) {
            }

            Class<?> geHideClass = cl.loadClass(CLASS_GEHIDE_APPS);
            sGetGeHideAppsMethod = findMethod(geHideClass, "getGeHideAppsList", Context.class);

            Class<?> appInfoClass = cl.loadClass(CLASS_APPINFO);
            sTitleField = findFieldInHierarchy(appInfoClass, "title");
            sComponentNameField = findFieldInHierarchy(appInfoClass, "componentName");

            Class<?> componentNameClass = cl.loadClass("android.content.ComponentName");
            sGetPackageNameMethod = findMethod(componentNameClass, "getPackageName");

            Class<?> adapterItemClass = cl.loadClass(CLASS_ADAPTER_ITEM);
            sAsAppMethod = findMethod(adapterItemClass, "asApp", appInfoClass);

            Class<?> hotseatClass = cl.loadClass(CLASS_HOTSEAT_QSB);
            sFallbackSearchViewField = findFieldInHierarchy(hotseatClass, "mFallbackSearchView");
            Method onSearchResult = findMethod(hotseatClass, "onSearchResult", String.class, ArrayList.class);
            hook(onSearchResult, StaleResultBlockerHooker.class);

            log("Search enhancement hooks registered successfully.");
        } catch (Throwable t) {
            log("Failed to register search enhancement hooks", t);
        }
    }

    @XposedHooker
    private static class SearchConversionsHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            try {
                String q = normalize((String) callback.getArgs()[0]);
                String[] conversions = (String[]) callback.getArgs()[1];
                if (TextUtils.isEmpty(q) || conversions == null || conversions.length == 0) return;

                ArrayList<String> list = new ArrayList<>(conversions.length);
                for (String c : conversions) {
                    String n = normalize(c);
                    if (!TextUtils.isEmpty(n) && !n.equals(q)) list.add(n);
                }
                if (!list.isEmpty()) sQueryConversions.put(q, list);
            } catch (Throwable t) {
                module.log("SearchConversionsHooker failed", t);
            }
        }
    }

    @XposedHooker
    private static class StaleResultBlockerHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            try {
                Object thiz = callback.getThisObject();
                String callbackQuery = normalize((String) callback.getArgs()[0]);

                Object editTextObj = sFallbackSearchViewField.get(thiz);
                if (!(editTextObj instanceof TextView)) return;

                String current = normalize(String.valueOf(((TextView) editTextObj).getText()));
                if (!TextUtils.isEmpty(current) && !current.equals(callbackQuery)) {
                    callback.returnAndSkip(null);
                }
            } catch (Throwable t) {
                module.log("StaleResultBlockerHooker failed", t);
            }
        }
    }

    @XposedHooker
    private static class SearchAlgorithmHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            try {
                Context context = (Context) callback.getArgs()[0];
                List<?> originalApps = (List<?>) callback.getArgs()[1];
                String rawQuery = (String) callback.getArgs()[2];

                ArrayList<Object> result = buildSearchResults(context, originalApps, rawQuery);
                callback.returnAndSkip(result);
            } catch (Throwable t) {
                module.log("SearchAlgorithmHooker failed, fallback original", t);
            }
        }
    }

    private static ArrayList<Object> buildSearchResults(Context context, List<?> originalApps, String rawQuery) throws Throwable {
        String queryNorm = normalize(rawQuery);
        ArrayList<Object> output = new ArrayList<>();
        if (TextUtils.isEmpty(queryNorm)) return output;

        LinkedHashSet<String> queryVariants = buildQueryVariants(rawQuery);

        List<String> conversions = sQueryConversions.remove(queryNorm);
        if (conversions != null) {
            for (String c : conversions) {
                queryVariants.addAll(buildQueryVariants(c));
            }
        }

        ArrayList<Object> candidates = new ArrayList<>();
        if (originalApps != null) candidates.addAll(originalApps);

        if (context != null && sGetGeHideAppsMethod != null) {
            Object ge = sGetGeHideAppsMethod.invoke(null, context);
            if (ge instanceof List) candidates.addAll((List<?>) ge);
        }

        ArrayList<ScoredApp> scored = new ArrayList<>(candidates.size());
        HashSet<String> dedupe = new HashSet<>();

        for (Object app : candidates) {
            if (app == null) continue;

            String key = appKey(app);
            if (!TextUtils.isEmpty(key) && !dedupe.add(key)) continue;

            String title = getAppTitle(app);
            String pkg = getPackageName(app);

            AppForms forms = buildAppForms(title, pkg);

            int best = 0;
            for (String q : queryVariants) {
                int s = scoreWithForms(q, forms);
                if (s > best) best = s;
            }

            if (best <= 0) continue;
            scored.add(new ScoredApp(app, best, forms.titleNorm, forms.pkgNorm));
        }

        scored.sort((a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            int t = a.normTitle.compareTo(b.normTitle);
            if (t != 0) return t;
            return a.normPkg.compareTo(b.normPkg);
        });

        int count = Math.min(MAX_RESULTS, scored.size());
        for (int i = 0; i < count; i++) {
            output.add(sAsAppMethod.invoke(null, scored.get(i).app));
        }

        return output;
    }

    private static LinkedHashSet<String> buildQueryVariants(String raw) {
        LinkedHashSet<String> v = new LinkedHashSet<>();
        String q = normalize(raw);
        if (TextUtils.isEmpty(q)) return v;

        v.add(q);

        String hira = normalize(toHiragana(q));
        String kata = normalize(toKatakana(q));
        String kanaLooseQ = normalize(kanaLoose(q));
        String kanaLooseH = normalize(kanaLoose(hira));
        String kanaLooseK = normalize(kanaLoose(kata));

        if (!TextUtils.isEmpty(hira)) v.add(hira);
        if (!TextUtils.isEmpty(kata)) v.add(kata);
        if (!TextUtils.isEmpty(kanaLooseQ)) v.add(kanaLooseQ);
        if (!TextUtils.isEmpty(kanaLooseH)) v.add(kanaLooseH);
        if (!TextUtils.isEmpty(kanaLooseK)) v.add(kanaLooseK);

        String latin = normalize(toLatinAscii(q));
        if (!TextUtils.isEmpty(latin)) v.add(latin);

        if (isMostlyLatin(q)) {
            String kFromLatin = normalize(romanToKatakana(q));
            if (!TextUtils.isEmpty(kFromLatin)) {
                String hFromLatin = normalize(toHiragana(kFromLatin));
                v.add(kFromLatin);
                if (!TextUtils.isEmpty(hFromLatin)) v.add(hFromLatin);
                String loose = normalize(kanaLoose(kFromLatin));
                if (!TextUtils.isEmpty(loose)) v.add(loose);
            }
        }

        if (isLikelyKana(q)) {
            String kanaLatin = normalize(toLatinAscii(q));
            if (!TextUtils.isEmpty(kanaLatin)) v.add(kanaLatin);
        }

        String qCho = normalize(toChoseongQuery(q));
        if (!TextUtils.isEmpty(qCho)) v.add(qCho);

        String qJamo = normalize(decomposeHangulToJamo(q));
        if (!TextUtils.isEmpty(qJamo)) v.add(qJamo);

        return v;
    }

    private static AppForms buildAppForms(String titleRaw, String pkgRaw) {
        String titleNorm = normalize(titleRaw);
        String titleHira = normalize(toHiragana(titleRaw));
        String titleKata = normalize(toKatakana(titleRaw));
        String titleKanaLoose = normalize(kanaLoose(titleRaw));
        String titleLatin = normalize(toLatinAscii(titleRaw));
        String titleCho = normalize(extractChoseong(titleRaw));
        String titleJamo = normalize(decomposeHangulToJamo(titleRaw));
        String pkgNorm = normalize(pkgRaw);

        return new AppForms(
                titleNorm, titleHira, titleKata, titleKanaLoose, titleLatin, titleCho, titleJamo, pkgNorm
        );
    }

    private static int scoreWithForms(String q, AppForms f) {
        if (TextUtils.isEmpty(q)) return 0;
        int best = 0;

        best = Math.max(best, scoreBasic(q, f.titleNorm,
                SCORE_TITLE_EXACT, SCORE_TITLE_PREFIX, SCORE_TITLE_WORD_PREFIX, SCORE_TITLE_CONTAINS));

        best = Math.max(best, scoreBasic(q, f.titleHira,
                SCORE_KANA_EXACT, SCORE_KANA_PREFIX, SCORE_KANA_WORD_PREFIX, SCORE_KANA_CONTAINS));

        best = Math.max(best, scoreBasic(q, f.titleKata,
                SCORE_KANA_EXACT, SCORE_KANA_PREFIX, SCORE_KANA_WORD_PREFIX, SCORE_KANA_CONTAINS));

        best = Math.max(best, scoreBasic(q, f.titleKanaLoose,
                SCORE_KANA_EXACT - 40, SCORE_KANA_PREFIX - 40, SCORE_KANA_WORD_PREFIX - 30, SCORE_KANA_CONTAINS));

        if (!TextUtils.isEmpty(f.titleLatin)) {
            best = Math.max(best, scoreBasic(q, f.titleLatin,
                    SCORE_LATIN_EXACT, SCORE_LATIN_PREFIX, SCORE_LATIN_WORD_PREFIX, SCORE_LATIN_CONTAINS));
        }

        if (!TextUtils.isEmpty(f.titleCho)) {
            best = Math.max(best, scoreBasic(q, f.titleCho,
                    SCORE_CHOSEONG_EXACT, SCORE_CHOSEONG_PREFIX, SCORE_CHOSEONG_WORD_PREFIX, SCORE_CHOSEONG_CONTAINS));
        }

        if (!TextUtils.isEmpty(f.titleJamo)) {
            best = Math.max(best, scoreBasic(q, f.titleJamo,
                    SCORE_JAMO_EXACT, SCORE_JAMO_PREFIX, SCORE_JAMO_WORD_PREFIX, SCORE_JAMO_CONTAINS));
        }

        best = Math.max(best, scorePackage(q, f.pkgNorm));

        return best;
    }

    private static int scoreBasic(String q, String target, int exact, int prefix, int wordPrefix, int contains) {
        if (TextUtils.isEmpty(target)) return 0;
        if (target.equals(q)) return exact;
        if (target.startsWith(q)) return prefix;
        if (matchesWordPrefix(q, target)) return wordPrefix;
        if (q.length() >= 2 && target.contains(q)) return contains;
        return 0;
    }

    private static int scorePackage(String q, String pkgNorm) {
        if (TextUtils.isEmpty(pkgNorm) || q.length() < 2) return 0;

        int best = 0;
        String[] tokens = pkgNorm.split("[._\\-]+");
        for (String t : tokens) {
            if (TextUtils.isEmpty(t) || t.length() < 2) continue;
            if (PACKAGE_STOPWORDS.contains(t)) continue;

            if (t.equals(q)) best = Math.max(best, SCORE_PKG_EXACT);
            else if (t.startsWith(q)) best = Math.max(best, SCORE_PKG_PREFIX);
            else if (q.length() >= 3 && t.contains(q)) best = Math.max(best, SCORE_PKG_CONTAINS);
        }
        return best;
    }

    private static boolean matchesWordPrefix(String q, String text) {
        String[] words = text.split("[\\s\\-_.()\\[\\]/・]+");
        for (String w : words) {
            if (w.startsWith(q)) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static boolean isMostlyLatin(String s) {
        int latin = 0;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            }
        }
        return letters > 0 && latin * 100 / letters >= 70;
    }

    private static boolean isLikelyKana(String s) {
        int kana = 0;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (isHiragana(c) || isKatakana(c)) kana++;
            }
        }
        return letters > 0 && kana * 100 / letters >= 70;
    }

    private static boolean isHiragana(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    private static boolean isKatakana(char c) {
        return c >= 0x30A0 && c <= 0x30FF;
    }

    private static String toHiragana(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0x30A1 && c <= 0x30F6) out.append((char) (c - 0x60));
            else out.append(c);
        }
        return out.toString();
    }

    private static String toKatakana(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0x3041 && c <= 0x3096) out.append((char) (c + 0x60));
            else out.append(c);
        }
        return out.toString();
    }

    private static String kanaLoose(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String k = toKatakana(s);
        StringBuilder out = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == 'ー') continue;
            if (c == '・') continue;
            out.append(expandSmallKana(c));
        }
        return out.toString();
    }

    private static char expandSmallKana(char c) {
        return switch (c) {
            case 'ァ' -> 'ア';
            case 'ィ' -> 'イ';
            case 'ゥ' -> 'ウ';
            case 'ェ' -> 'エ';
            case 'ォ' -> 'オ';
            case 'ャ' -> 'ヤ';
            case 'ュ' -> 'ユ';
            case 'ョ' -> 'ヨ';
            case 'ッ' -> 'ツ';
            case 'ヮ' -> 'ワ';
            case 'ヵ' -> 'カ';
            case 'ヶ' -> 'ケ';
            default -> c;
        };
    }

    private static String toLatinAscii(String s) {
        String r = transliterate("Any-Latin; Latin-ASCII", s);
        if (TextUtils.isEmpty(r)) return "";
        return r.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s._\\-]", "");
    }

    private static String romanToKatakana(String s) {
        String r = transliterate("Latin-Katakana", s);
        if (!TextUtils.isEmpty(r) && !r.equals(s)) return r;
        return romanToKatakanaBasic(s);
    }

    private static String transliterate(String id, String input) {
        if (TextUtils.isEmpty(input)) return "";
        try {
            Object tr = sIcuMap.get(id);
            if (tr == null) {
                Class<?> cls = Class.forName("android.icu.text.Transliterator");
                Method getInstance = cls.getMethod("getInstance", String.class);
                tr = getInstance.invoke(null, id);
                if (tr != null) sIcuMap.put(id, tr);
            }
            if (tr == null) return input;
            Method transliterate = tr.getClass().getMethod("transliterate", String.class);
            Object out = transliterate.invoke(tr, input);
            return out == null ? input : String.valueOf(out);
        } catch (Throwable ignore) {
            return input;
        }
    }

    private static String romanToKatakanaBasic(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String t = normalize(s).replaceAll("[^a-z]", "");
        if (TextUtils.isEmpty(t)) return "";

        StringBuilder out = new StringBuilder(t.length() * 2);
        int i = 0;
        while (i < t.length()) {
            if (i + 1 < t.length() && t.charAt(i) == t.charAt(i + 1) && isConsonant(t.charAt(i)) && t.charAt(i) != 'n') {
                out.append('ッ');
                i++;
                continue;
            }

            String m3 = i + 3 <= t.length() ? t.substring(i, i + 3) : null;
            String m2 = i + 2 <= t.length() ? t.substring(i, i + 2) : null;

            String k = null;
            int step = 1;

            if (m3 != null) {
                k = mapRomaji3(m3);
                if (k != null) step = 3;
            }
            if (k == null && m2 != null) {
                k = mapRomaji2(m2);
                if (k != null) step = 2;
            }
            if (k == null) {
                k = mapRomaji1(t.charAt(i));
            }

            if (k == null) {
                i++;
            } else {
                out.append(k);
                i += step;
            }
        }
        return out.toString();
    }

    private static boolean isConsonant(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }

    private static String mapRomaji3(String s) {
        return switch (s) {
            case "kya" -> "キャ";
            case "kyu" -> "キュ";
            case "kyo" -> "キョ";
            case "sha" -> "シャ";
            case "shu" -> "シュ";
            case "sho" -> "ショ";
            case "cha" -> "チャ";
            case "chu" -> "チュ";
            case "cho" -> "チョ";
            case "nya" -> "ニャ";
            case "nyu" -> "ニュ";
            case "nyo" -> "ニョ";
            case "hya" -> "ヒャ";
            case "hyu" -> "ヒュ";
            case "hyo" -> "ヒョ";
            case "mya" -> "ミャ";
            case "myu" -> "ミュ";
            case "myo" -> "ミョ";
            case "rya" -> "リャ";
            case "ryu" -> "リュ";
            case "ryo" -> "リョ";
            case "gya" -> "ギャ";
            case "gyu" -> "ギュ";
            case "gyo" -> "ギョ";
            case "bya" -> "ビャ";
            case "byu" -> "ビュ";
            case "byo" -> "ビョ";
            case "pya" -> "ピャ";
            case "pyu" -> "ピュ";
            case "pyo" -> "ピョ";
            case "shi" -> "シ";
            case "chi" -> "チ";
            case "tsu" -> "ツ";
            default -> null;
        };
    }

    private static String mapRomaji2(String s) {
        return switch (s) {
            case "ka" -> "カ";
            case "ki" -> "キ";
            case "ku" -> "ク";
            case "ke" -> "ケ";
            case "ko" -> "コ";
            case "sa" -> "サ";
            case "si" -> "シ";
            case "su" -> "ス";
            case "se" -> "セ";
            case "so" -> "ソ";
            case "ta" -> "タ";
            case "ti" -> "チ";
            case "tu" -> "ツ";
            case "te" -> "テ";
            case "to" -> "ト";
            case "na" -> "ナ";
            case "ni" -> "ニ";
            case "nu" -> "ヌ";
            case "ne" -> "ネ";
            case "no" -> "ノ";
            case "ha" -> "ハ";
            case "hi" -> "ヒ";
            case "hu" -> "フ";
            case "fu" -> "フ";
            case "he" -> "ヘ";
            case "ho" -> "ホ";
            case "ma" -> "マ";
            case "mi" -> "ミ";
            case "mu" -> "ム";
            case "me" -> "メ";
            case "mo" -> "モ";
            case "ya" -> "ヤ";
            case "yu" -> "ユ";
            case "yo" -> "ヨ";
            case "ra" -> "ラ";
            case "ri" -> "リ";
            case "ru" -> "ル";
            case "re" -> "レ";
            case "ro" -> "ロ";
            case "wa" -> "ワ";
            case "wo" -> "ヲ";
            case "ga" -> "ガ";
            case "gi" -> "ギ";
            case "gu" -> "グ";
            case "ge" -> "ゲ";
            case "go" -> "ゴ";
            case "za" -> "ザ";
            case "zi" -> "ジ";
            case "ji" -> "ジ";
            case "zu" -> "ズ";
            case "ze" -> "ゼ";
            case "zo" -> "ゾ";
            case "da" -> "ダ";
            case "di" -> "ヂ";
            case "du" -> "ヅ";
            case "de" -> "デ";
            case "do" -> "ド";
            case "ba" -> "バ";
            case "bi" -> "ビ";
            case "bu" -> "ブ";
            case "be" -> "ベ";
            case "bo" -> "ボ";
            case "pa" -> "パ";
            case "pi" -> "ピ";
            case "pu" -> "プ";
            case "pe" -> "ペ";
            case "po" -> "ポ";
            case "nn" -> "ン";
            default -> null;
        };
    }

    private static String mapRomaji1(char c) {
        return switch (c) {
            case 'a' -> "ア";
            case 'i' -> "イ";
            case 'u' -> "ウ";
            case 'e' -> "エ";
            case 'o' -> "オ";
            case 'n' -> "ン";
            default -> null;
        };
    }

    private static String toChoseongQuery(String q) {
        if (TextUtils.isEmpty(q)) return "";
        StringBuilder sb = new StringBuilder(q.length());
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (isCompatChoseong(c)) sb.append(c);
            else if (isHangulSyllable(c)) sb.append(extractChoseongChar(c));
            else return "";
        }
        return sb.toString();
    }

    private static String extractChoseong(String text) {
        if (TextUtils.isEmpty(text)) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHangulSyllable(c)) sb.append(extractChoseongChar(c));
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static boolean isHangulSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static boolean isCompatChoseong(char c) {
        return c >= 0x3131 && c <= 0x314E;
    }

    private static char extractChoseongChar(char syllable) {
        int code = syllable - 0xAC00;
        int idx = code / 588;
        if (idx < 0 || idx >= CHOSEONG.length) return syllable;
        return CHOSEONG[idx];
    }

    private static String decomposeHangulToJamo(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (isJamo(c) || Character.isLetterOrDigit(c) || Character.isSpaceChar(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim();
    }

    private static boolean isJamo(char c) {
        return (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F);
    }

    private static String getAppTitle(Object app) {
        try {
            Object o = sTitleField.get(app);
            return o == null ? "" : String.valueOf(o);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String getPackageName(Object app) {
        try {
            Object componentName = sComponentNameField.get(app);
            if (componentName == null) return "";
            Object pkg = sGetPackageNameMethod.invoke(componentName);
            return pkg == null ? "" : String.valueOf(pkg);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String appKey(Object app) {
        try {
            Object componentName = sComponentNameField.get(app);
            return componentName == null ? null : String.valueOf(componentName);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Method findMethod(Class<?> startClass, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findFieldInHierarchy(Class<?> startClass, String fieldName) throws NoSuchFieldException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record AppForms(String titleNorm, String titleHira, String titleKata,
                            String titleKanaLoose, String titleLatin, String titleCho,
                            String titleJamo, String pkgNorm) {
    }

    private record ScoredApp(Object app, int score, String normTitle, String normPkg) {
    }
}
