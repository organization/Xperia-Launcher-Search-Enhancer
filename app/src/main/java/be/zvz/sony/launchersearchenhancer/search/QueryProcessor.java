package be.zvz.sony.launchersearchenhancer.search;

import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.List;

import be.zvz.sony.launchersearchenhancer.record.AppForms;
import be.zvz.sony.launchersearchenhancer.text.HangulProcessor;
import be.zvz.sony.launchersearchenhancer.text.KanaConverter;
import be.zvz.sony.launchersearchenhancer.text.TextNormalizer;

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

        // Latin transliteration (also covers the kana→latin case since it's the same computation)
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
        return new AppForms(
                TextNormalizer.normalize(titleRaw),
                TextNormalizer.normalize(KanaConverter.toHiragana(titleRaw)),
                TextNormalizer.normalize(KanaConverter.toKatakana(titleRaw)),
                TextNormalizer.normalize(KanaConverter.kanaLoose(titleRaw)),
                TextNormalizer.normalize(KanaConverter.toLatinAscii(titleRaw)),
                TextNormalizer.normalize(HangulProcessor.extractChoseong(titleRaw)),
                TextNormalizer.normalize(HangulProcessor.decomposeToJamo(titleRaw)),
                TextNormalizer.normalize(pkgRaw)
        );
    }

    public static String pickBridgeCandidate(String current, List<String> recent) {
        if (recent == null || recent.isEmpty()) return "";
        for (String q : recent) {
            if (TextUtils.isEmpty(q) || q.length() < 2 || q.equals(current)) continue;
            if (current.startsWith(q) || q.startsWith(current)) return q;
        }
        return "";
    }

    private static void addIfNotEmpty(LinkedHashSet<String> set, String value) {
        if (!TextUtils.isEmpty(value)) set.add(value);
    }
}
