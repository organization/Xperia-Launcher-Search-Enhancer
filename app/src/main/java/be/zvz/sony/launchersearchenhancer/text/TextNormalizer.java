package be.zvz.sony.launchersearchenhancer.text;

import android.text.TextUtils;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    public static boolean isMostlyLatin(String s) {
        int latin = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            }
        }
        return letters > 0 && latin * 100 / letters >= 70;
    }

    public static boolean isHiragana(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    public static boolean isKatakana(char c) {
        return c >= 0x30A0 && c <= 0x30FF;
    }
}
