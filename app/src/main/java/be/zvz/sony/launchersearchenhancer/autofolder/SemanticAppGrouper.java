package be.zvz.sony.launchersearchenhancer.autofolder;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import be.zvz.sony.launchersearchenhancer.R;
import be.zvz.sony.launchersearchenhancer.reranker.SemanticReranker;

public final class SemanticAppGrouper {

    private static final float MIN_SIMILARITY = 0.34f;
    private static final float MIN_MARGIN = 0.04f;

    private static final Category[] CATEGORIES = {
            new Category(R.string.auto_folder_category_messaging, "Messaging", "messaging chat sms messenger social network sns dm kakaotalk line whatsapp telegram signal discord instagram facebook twitter x mastodon"),
            new Category(R.string.auto_folder_category_music, "Music", "music audio songs albums streaming radio podcast spotify youtube music soundcloud melon genie bugs apple music"),
            new Category(R.string.auto_folder_category_video, "Video", "video movies tv streaming player clips youtube netflix disney twitch tiktok media watch"),
            new Category(R.string.auto_folder_category_photo, "Photos", "photo camera gallery image pictures editor scanner filters instagram lightroom snapseed"),
            new Category(R.string.auto_folder_category_games, "Games", "games play arcade puzzle action rpg strategy sports battle casino chess steam xbox playstation"),
            new Category(R.string.auto_folder_category_shopping, "Shopping", "shopping store market ecommerce buy deals coupons delivery amazon ebay coupang naver shopping aliexpress"),
            new Category(R.string.auto_folder_category_finance, "Finance", "finance banking card wallet pay investment stocks crypto insurance money tax budget"),
            new Category(R.string.auto_folder_category_productivity, "Productivity", "productivity office document notes calendar todo tasks email spreadsheet presentation pdf drive cloud notion word excel"),
            new Category(R.string.auto_folder_category_tools, "Tools", "tools utility system settings files cleaner backup security keyboard calculator clock weather vpn launcher"),
            new Category(R.string.auto_folder_category_browser, "Browser", "browser internet web search chrome firefox edge duckduckgo opera brave bookmarks"),
            new Category(R.string.auto_folder_category_maps, "Maps", "maps navigation transit transport taxi rides bus subway train gps uber kakao t naver map google maps"),
            new Category(R.string.auto_folder_category_travel, "Travel", "travel hotels flights booking airline trip itinerary passport translation airbnb agoda expedia"),
            new Category(R.string.auto_folder_category_food, "Food", "food restaurant delivery cooking recipe coffee grocery meal order baemin yogiyo uber eats"),
            new Category(R.string.auto_folder_category_health, "Health", "health fitness workout exercise medical hospital pharmacy meditation sleep diet wellness samsung health"),
            new Category(R.string.auto_folder_category_education, "Education", "education learning language study school course dictionary books flashcards classroom duolingo"),
            new Category(R.string.auto_folder_category_news, "News", "news reading articles magazine newspaper rss books comics kindle reddit feed")
    };

    private final SemanticReranker reranker;
    private final ConcurrentHashMap<String, float[]> categoryVectors = new ConcurrentHashMap<>();

    public SemanticAppGrouper(SemanticReranker reranker) {
        this.reranker = reranker;
    }

    public List<Group> group(Context context, List<AppCandidate> apps) throws Exception {
        return group(context, apps, null);
    }

    public List<Group> group(Context context, List<AppCandidate> apps, Context labelContext) throws Exception {
        if (context == null || apps == null || apps.size() < 2) return Collections.emptyList();

        Map<Category, ArrayList<AppCandidate>> buckets = new LinkedHashMap<>();
        for (Category category : CATEGORIES) {
            buckets.put(category, new ArrayList<>());
            categoryVector(context, category);
        }

        for (AppCandidate app : apps) {
            float[] appVector = reranker.embedForText(context, app.embeddingText());
            if (appVector == null) continue;

            Category best = null;
            float bestScore = -1f;
            float secondScore = -1f;

            for (Category category : CATEGORIES) {
                float[] categoryVector = categoryVector(context, category);
                if (categoryVector == null) continue;

                float score = SemanticReranker.cosineSimilarity(appVector, categoryVector);
                if (score > bestScore) {
                    secondScore = bestScore;
                    bestScore = score;
                    best = category;
                } else if (score > secondScore) {
                    secondScore = score;
                }
            }

            if (best != null && bestScore >= MIN_SIMILARITY && bestScore - secondScore >= MIN_MARGIN) {
                buckets.get(best).add(app);
            }
        }

        ArrayList<Group> groups = new ArrayList<>();
        for (Map.Entry<Category, ArrayList<AppCandidate>> entry : buckets.entrySet()) {
            ArrayList<AppCandidate> groupedApps = entry.getValue();
            if (groupedApps.size() >= 2) {
                groups.add(new Group(entry.getKey().label(labelContext), groupedApps));
            }
        }
        groups.sort(Comparator.comparingInt(Group::firstIndex));
        return groups;
    }

    private float[] categoryVector(Context context, Category category) throws Exception {
        float[] cached = categoryVectors.get(category.prompt);
        if (cached != null) return cached;

        float[] vector = reranker.embedForText(context, category.prompt);
        if (vector != null) {
            categoryVectors.put(category.prompt, vector);
        }
        return vector;
    }

    private record Category(int labelResId, String fallbackLabel, String prompt) {
        String label(Context context) {
            try {
                if (context != null) return context.getString(labelResId);
            } catch (Throwable ignored) {
            }
            return fallbackLabel;
        }
    }

    public static final class AppCandidate {
        public final Object app;
        public final String key;
        public final String title;
        public final String packageName;
        public final int originalIndex;

        public AppCandidate(Object app, String key, String title, String packageName, int originalIndex) {
            this.app = app;
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.packageName = packageName == null ? "" : packageName;
            this.originalIndex = originalIndex;
        }

        private String embeddingText() {
            String pkgTokens = packageName
                    .replace('.', ' ')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .toLowerCase(Locale.ROOT);
            if (TextUtils.isEmpty(title)) return pkgTokens;
            return title + " " + pkgTokens + " mobile app";
        }
    }

    public static final class Group {
        public final String label;
        public final List<AppCandidate> apps;
        private final int firstIndex;

        private Group(String label, List<AppCandidate> apps) {
            this.label = label;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            int first = Integer.MAX_VALUE;
            for (AppCandidate app : apps) {
                first = Math.min(first, app.originalIndex);
            }
            this.firstIndex = first == Integer.MAX_VALUE ? -1 : first;
        }

        public int firstIndex() {
            return firstIndex;
        }
    }
}
