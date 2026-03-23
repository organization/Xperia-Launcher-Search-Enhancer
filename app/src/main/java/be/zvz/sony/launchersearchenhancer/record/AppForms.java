package be.zvz.sony.launchersearchenhancer.record;

import java.util.List;

public record AppForms(
        String titleNorm, String titleHira, String titleKata,
        String titleKanaLoose, String titleLatin, String titleCho,
        String titleJamo, String pkgNorm, List<String> pkgTokens) {
}
