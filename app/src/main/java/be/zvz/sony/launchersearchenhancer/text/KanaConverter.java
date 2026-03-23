package be.zvz.sony.launchersearchenhancer.text;

import android.text.TextUtils;

import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class KanaConverter {

    private KanaConverter() {}

    private record TransliteratorEntry(Object instance, Method method) {}

    private static final ConcurrentHashMap<String, TransliteratorEntry> sIcuCache = new ConcurrentHashMap<>();
    private static final Pattern LATIN_ASCII_STRIP = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\s._\\-]");
    private static final Pattern NON_AZ = Pattern.compile("[^a-z]");

    public static String toHiragana(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String n = TextNormalizer.isAscii(s) ? s : Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            out.append(c >= 0x30A1 && c <= 0x30F6 ? (char) (c - 0x60) : c);
        }
        return out.toString();
    }

    public static String toKatakana(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String n = TextNormalizer.isAscii(s) ? s : Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            out.append(c >= 0x3041 && c <= 0x3096 ? (char) (c + 0x60) : c);
        }
        return out.toString();
    }

    public static String kanaLoose(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String k = toKatakana(s);
        StringBuilder out = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == 'ー' || c == '・') continue;
            out.append(expandSmallKana(c));
        }
        return out.toString();
    }

    public static String toLatinAscii(String s) {
        String r = transliterate("Any-Latin; Latin-ASCII", s);
        if (TextUtils.isEmpty(r)) return "";
        return LATIN_ASCII_STRIP.matcher(r).replaceAll("");
    }

    public static String romanToKatakana(String s) {
        String r = transliterate("Latin-Katakana", s);
        if (!TextUtils.isEmpty(r) && !r.equals(s)) return r;
        return romanToKatakanaBasic(s);
    }

    private static char expandSmallKana(char c) {
        return switch (c) {
            case 'ァ' -> 'ア'; case 'ィ' -> 'イ'; case 'ゥ' -> 'ウ';
            case 'ェ' -> 'エ'; case 'ォ' -> 'オ'; case 'ャ' -> 'ヤ';
            case 'ュ' -> 'ユ'; case 'ョ' -> 'ヨ'; case 'ッ' -> 'ツ';
            case 'ヮ' -> 'ワ'; case 'ヵ' -> 'カ'; case 'ヶ' -> 'ケ';
            default -> c;
        };
    }

    private static String transliterate(String id, String input) {
        if (TextUtils.isEmpty(input)) return "";
        try {
            TransliteratorEntry entry = sIcuCache.get(id);
            if (entry == null) {
                Class<?> cls = Class.forName("android.icu.text.Transliterator");
                Method getInstance = cls.getMethod("getInstance", String.class);
                Object tr = getInstance.invoke(null, id);
                if (tr == null) return input;
                Method m = tr.getClass().getMethod("transliterate", String.class);
                entry = new TransliteratorEntry(tr, m);
                sIcuCache.put(id, entry);
            }
            Object out = entry.method.invoke(entry.instance, input);
            return out == null ? input : String.valueOf(out);
        } catch (Throwable ignore) {
            return input;
        }
    }

    // --- Romaji → Katakana fallback ---

    private static String romanToKatakanaBasic(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String t = NON_AZ.matcher(TextNormalizer.normalize(s)).replaceAll("");
        if (TextUtils.isEmpty(t)) return "";

        StringBuilder out = new StringBuilder(t.length() * 2);
        int i = 0;
        while (i < t.length()) {
            if (i + 1 < t.length() && t.charAt(i) == t.charAt(i + 1)
                    && isConsonant(t.charAt(i)) && t.charAt(i) != 'n') {
                out.append('ッ');
                i++;
                continue;
            }

            String k = null;
            int step = 1;

            if (i + 3 <= t.length()) {
                k = mapRomaji3(t.substring(i, i + 3));
                if (k != null) step = 3;
            }
            if (k == null && i + 2 <= t.length()) {
                k = mapRomaji2(t.substring(i, i + 2));
                if (k != null) step = 2;
            }
            if (k == null) {
                k = mapRomaji1(t.charAt(i));
            }

            if (k != null) out.append(k);
            i += step;
        }
        return out.toString();
    }

    private static boolean isConsonant(char c) {
        return c >= 'a' && c <= 'z' && "aeiou".indexOf(c) < 0;
    }

    private static String mapRomaji3(String s) {
        return switch (s) {
            case "kya" -> "キャ"; case "kyu" -> "キュ"; case "kyo" -> "キョ";
            case "sha" -> "シャ"; case "shu" -> "シュ"; case "sho" -> "ショ";
            case "cha" -> "チャ"; case "chu" -> "チュ"; case "cho" -> "チョ";
            case "nya" -> "ニャ"; case "nyu" -> "ニュ"; case "nyo" -> "ニョ";
            case "hya" -> "ヒャ"; case "hyu" -> "ヒュ"; case "hyo" -> "ヒョ";
            case "mya" -> "ミャ"; case "myu" -> "ミュ"; case "myo" -> "ミョ";
            case "rya" -> "リャ"; case "ryu" -> "リュ"; case "ryo" -> "リョ";
            case "gya" -> "ギャ"; case "gyu" -> "ギュ"; case "gyo" -> "ギョ";
            case "bya" -> "ビャ"; case "byu" -> "ビュ"; case "byo" -> "ビョ";
            case "pya" -> "ピャ"; case "pyu" -> "ピュ"; case "pyo" -> "ピョ";
            case "shi" -> "シ";   case "chi" -> "チ";   case "tsu" -> "ツ";
            default -> null;
        };
    }

    private static String mapRomaji2(String s) {
        return switch (s) {
            case "ka" -> "カ"; case "ki" -> "キ"; case "ku" -> "ク"; case "ke" -> "ケ"; case "ko" -> "コ";
            case "sa" -> "サ"; case "si" -> "シ"; case "su" -> "ス"; case "se" -> "セ"; case "so" -> "ソ";
            case "ta" -> "タ"; case "ti" -> "チ"; case "tu" -> "ツ"; case "te" -> "テ"; case "to" -> "ト";
            case "na" -> "ナ"; case "ni" -> "ニ"; case "nu" -> "ヌ"; case "ne" -> "ネ"; case "no" -> "ノ";
            case "ha" -> "ハ"; case "hi" -> "ヒ"; case "hu", "fu" -> "フ"; case "he" -> "ヘ"; case "ho" -> "ホ";
            case "ma" -> "マ"; case "mi" -> "ミ"; case "mu" -> "ム"; case "me" -> "メ"; case "mo" -> "モ";
            case "ya" -> "ヤ"; case "yu" -> "ユ"; case "yo" -> "ヨ";
            case "ra" -> "ラ"; case "ri" -> "リ"; case "ru" -> "ル"; case "re" -> "レ"; case "ro" -> "ロ";
            case "wa" -> "ワ"; case "wo" -> "ヲ";
            case "ga" -> "ガ"; case "gi" -> "ギ"; case "gu" -> "グ"; case "ge" -> "ゲ"; case "go" -> "ゴ";
            case "za" -> "ザ"; case "zi", "ji" -> "ジ"; case "zu" -> "ズ"; case "ze" -> "ゼ"; case "zo" -> "ゾ";
            case "da" -> "ダ"; case "di" -> "ヂ"; case "du" -> "ヅ"; case "de" -> "デ"; case "do" -> "ド";
            case "ba" -> "バ"; case "bi" -> "ビ"; case "bu" -> "ブ"; case "be" -> "ベ"; case "bo" -> "ボ";
            case "pa" -> "パ"; case "pi" -> "ピ"; case "pu" -> "プ"; case "pe" -> "ペ"; case "po" -> "ポ";
            case "nn" -> "ン";
            default -> null;
        };
    }

    private static String mapRomaji1(char c) {
        return switch (c) {
            case 'a' -> "ア"; case 'i' -> "イ"; case 'u' -> "ウ";
            case 'e' -> "エ"; case 'o' -> "オ"; case 'n' -> "ン";
            default -> null;
        };
    }
}
