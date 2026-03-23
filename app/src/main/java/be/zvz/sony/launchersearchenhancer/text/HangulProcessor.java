package be.zvz.sony.launchersearchenhancer.text;

import android.text.TextUtils;

import java.text.Normalizer;

public final class HangulProcessor {

    private HangulProcessor() {}

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    public static String toChoseongQuery(String q) {
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

    public static String extractChoseong(String text) {
        if (TextUtils.isEmpty(text)) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHangulSyllable(c)) sb.append(extractChoseongChar(c));
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public static String decomposeToJamo(String s) {
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

    private static boolean isHangulSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static boolean isCompatChoseong(char c) {
        return c >= 0x3131 && c <= 0x314E;
    }

    private static char extractChoseongChar(char syllable) {
        int idx = (syllable - 0xAC00) / 588;
        return (idx >= 0 && idx < CHOSEONG.length) ? CHOSEONG[idx] : syllable;
    }

    private static boolean isJamo(char c) {
        return (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F);
    }
}
