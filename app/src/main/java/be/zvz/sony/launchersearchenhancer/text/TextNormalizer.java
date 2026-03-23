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

    public enum Script { LATIN, CJK, HANGUL, MIXED }

    public static Script detectScript(String s) {
        if (TextUtils.isEmpty(s)) return Script.MIXED;
        boolean hasLatin = false, hasCjk = false, hasHangul = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLatin = true;
            else if (isHiragana(c) || isKatakana(c) || (c >= 0x4E00 && c <= 0x9FFF)) hasCjk = true;
            else if (c >= 0xAC00 && c <= 0xD7A3) hasHangul = true;
            else return Script.MIXED;
        }
        int count = (hasLatin ? 1 : 0) + (hasCjk ? 1 : 0) + (hasHangul ? 1 : 0);
        if (count != 1) return Script.MIXED;
        if (hasLatin) return Script.LATIN;
        if (hasCjk) return Script.CJK;
        return Script.HANGUL;
    }

    public static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }
}
