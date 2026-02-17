package be.zvz.sony.launchersearchenhancer.record;

public record ScoredApp(
        Object app, int score, String normTitle, String normPkg,
                        String rawTitle, String rawPkg) {
    public ScoredApp(Object app, int score, String normTitle, String normPkg, String rawTitle, String rawPkg) {
        this.app = app;
        this.score = score;
        this.normTitle = normTitle;
        this.normPkg = normPkg;
        this.rawTitle = rawTitle == null ? "" : rawTitle;
        this.rawPkg = rawPkg == null ? "" : rawPkg;
    }
}
