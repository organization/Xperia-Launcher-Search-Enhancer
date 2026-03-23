package be.zvz.sony.launchersearchenhancer.search;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import be.zvz.sony.launchersearchenhancer.record.AppForms;
import be.zvz.sony.launchersearchenhancer.text.HangulProcessor;
import be.zvz.sony.launchersearchenhancer.text.KanaConverter;
import be.zvz.sony.launchersearchenhancer.text.TextNormalizer;
import be.zvz.sony.launchersearchenhancer.text.TextNormalizer.Script;

public final class QueryProcessor {

    private QueryProcessor() {}

    public static LinkedHashSet<String> buildQueryVariants(String raw) {
        LinkedHashSet<String> v = new LinkedHashSet<>();
        String q = TextNormalizer.normalize(raw);
        if (TextUtils.isEmpty(q)) return v;

        v.add(q);

        String hira = TextNormalizer.normalize(KanaConverter.toHiragana(q));
        String kata = TextNormalizer.normalize(KanaConverter.toKatakana(q));

        addIfNotEmpty(v, hira);
        addIfNotEmpty(v, kata);
        addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.kanaLoose(q)));
        addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.kanaLoose(hira)));
        addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.kanaLoose(kata)));

        addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.toLatinAscii(q)));

        if (TextNormalizer.isMostlyLatin(q)) {
            String kFromLatin = TextNormalizer.normalize(KanaConverter.romanToKatakana(q));
            if (!TextUtils.isEmpty(kFromLatin)) {
                v.add(kFromLatin);
                addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.toHiragana(kFromLatin)));
                addIfNotEmpty(v, TextNormalizer.normalize(KanaConverter.kanaLoose(kFromLatin)));
            }
        }

        addIfNotEmpty(v, TextNormalizer.normalize(HangulProcessor.toChoseongQuery(q)));
        addIfNotEmpty(v, TextNormalizer.normalize(HangulProcessor.decomposeToJamo(q)));

        return v;
    }

    public static AppForms buildAppForms(String titleRaw, String pkgRaw) {
        String titleNorm = TextNormalizer.normalize(titleRaw);
        String pkgNorm = TextNormalizer.normalize(pkgRaw);
        Script script = TextNormalizer.detectScript(titleRaw);

        String titleHira, titleKata, titleKanaLoose, titleLatin, titleCho, titleJamo;

        switch (script) {
            case LATIN -> {
                titleHira = "";
                titleKata = "";
                titleKanaLoose = "";
                titleLatin = titleNorm; // already latin
                titleCho = "";
                titleJamo = "";
            }
            case HANGUL -> {
                titleHira = "";
                titleKata = "";
                titleKanaLoose = "";
                titleLatin = TextNormalizer.normalize(KanaConverter.toLatinAscii(titleRaw));
                titleCho = TextNormalizer.normalize(HangulProcessor.extractChoseong(titleRaw));
                titleJamo = TextNormalizer.normalize(HangulProcessor.decomposeToJamo(titleRaw));
            }
            default -> {
                // CJK or MIXED: compute all forms
                titleHira = TextNormalizer.normalize(KanaConverter.toHiragana(titleRaw));
                titleKata = TextNormalizer.normalize(KanaConverter.toKatakana(titleRaw));
                titleKanaLoose = TextNormalizer.normalize(KanaConverter.kanaLoose(titleRaw));
                titleLatin = TextNormalizer.normalize(KanaConverter.toLatinAscii(titleRaw));
                titleCho = TextNormalizer.normalize(HangulProcessor.extractChoseong(titleRaw));
                titleJamo = TextNormalizer.normalize(HangulProcessor.decomposeToJamo(titleRaw));
            }
        }

        List<String> pkgTokens = splitPkgTokens(pkgNorm);

        return new AppForms(titleNorm, titleHira, titleKata, titleKanaLoose,
                titleLatin, titleCho, titleJamo, pkgNorm, pkgTokens);
    }

    public static String pickBridgeCandidate(String current, List<String> recent) {
        if (recent == null || recent.isEmpty()) return "";
        for (String q : recent) {
            if (TextUtils.isEmpty(q) || q.length() < 2 || q.equals(current)) continue;
            if (current.startsWith(q) || q.startsWith(current)) return q;
        }
        return "";
    }

    private static List<String> splitPkgTokens(String pkgNorm) {
        if (TextUtils.isEmpty(pkgNorm)) return Collections.emptyList();
        String[] parts = AppScorer.PKG_SPLIT.split(pkgNorm);
        ArrayList<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!TextUtils.isEmpty(p)) tokens.add(p);
        }
        return Collections.unmodifiableList(tokens);
    }

    private static void addIfNotEmpty(LinkedHashSet<String> set, String value) {
        if (!TextUtils.isEmpty(value)) set.add(value);
    }
}
