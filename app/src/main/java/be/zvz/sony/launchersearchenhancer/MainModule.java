package be.zvz.sony.launchersearchenhancer;

import android.annotation.SuppressLint;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import be.zvz.sony.launchersearchenhancer.record.AppForms;
import be.zvz.sony.launchersearchenhancer.record.ScoredApp;
import be.zvz.sony.launchersearchenhancer.reranker.SemanticReranker;
import be.zvz.sony.launchersearchenhancer.search.AppScorer;
import be.zvz.sony.launchersearchenhancer.search.QueryProcessor;
import be.zvz.sony.launchersearchenhancer.store.LearningStore;
import be.zvz.sony.launchersearchenhancer.store.PendingQueryStore;
import be.zvz.sony.launchersearchenhancer.store.QueryHistoryStore;
import be.zvz.sony.launchersearchenhancer.store.SearchSessionStore;
import be.zvz.sony.launchersearchenhancer.text.TextNormalizer;
import be.zvz.sony.launchersearchenhancer.util.ReflectionUtils;
import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class MainModule extends XposedModule {

    private static final String TAG = "LauncherSearchEnhancer";
    private static final String TARGET_PACKAGE = "com.sonymobile.launcher";
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MIN_RESULTS = 3;
    private static final int MAX_RESULTS_CAP = 7;

    private static final String CLASS_DEFAULT_SEARCH_ALGO = "com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm";
    private static final String CLASS_GEHIDE_APPS = "com.sonymobile.launcher.gameenhancer.GeHideAppsList";
    private static final String CLASS_APPINFO = "com.android.launcher3.model.data.AppInfo";
    private static final String CLASS_ADAPTER_ITEM = "com.android.launcher3.allapps.BaseAllAppsAdapter$AdapterItem";
    private static final String CLASS_HOTSEAT_QSB = "com.android.searchlauncher.HotseatQsbWidget";
    private static final String CLASS_ITEM_CLICK_HANDLER = "com.android.launcher3.touch.ItemClickHandler";

    private static XposedModule module;

    // Reflection handles (set once during hook registration)
    private static Method sAsAppMethod;
    private static Method sGetGeHideAppsMethod;
    private static Field sTitleField;
    private static Field sComponentNameField;
    private static Method sGetPackageNameMethod;
    private static Field sFallbackSearchViewField;
    private static Method sGetTargetComponentMethod;

    // Shared state
    private static final ConcurrentHashMap<String, List<String>> sQueryConversions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AppForms> sAppFormsCache = new ConcurrentHashMap<>();
    private static final SemanticReranker sSemanticReranker = new SemanticReranker();
    private static final LearningStore sLearningStore = new LearningStore();
    private static final QueryHistoryStore sQueryHistoryStore = new QueryHistoryStore();
    private static final SearchSessionStore sSearchSessionStore = new SearchSessionStore();
    private static final PendingQueryStore sPendingQueryStore = new PendingQueryStore();

    // UsageStats cache (populated once per package load, keyed by package name)
    private static volatile Map<String, Integer> sUsageBonusCache;

    // --- Lifecycle ---

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        module = this;
        log(Log.INFO, TAG, "Init module");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader cl = param.getClassLoader();

            Class<?> searchAlgoClass = cl.loadClass(CLASS_DEFAULT_SEARCH_ALGO);
            Method getTitleMatchResult = ReflectionUtils.findMethod(searchAlgoClass,
                    "getTitleMatchResult", Context.class, List.class, String.class);
            hook(getTitleMatchResult).intercept(new SearchAlgorithmHooker());

            try {
                Class<?> searchCallbackClass = cl.loadClass("com.android.launcher3.search.SearchCallback");
                Method doSearch = ReflectionUtils.findMethod(searchAlgoClass,
                        "doSearch", String.class, String[].class, searchCallbackClass);
                hook(doSearch).intercept(new SearchConversionsHooker());
            } catch (Throwable ignored) {
            }

            Class<?> geHideClass = cl.loadClass(CLASS_GEHIDE_APPS);
            sGetGeHideAppsMethod = ReflectionUtils.findMethod(geHideClass, "getGeHideAppsList", Context.class);

            Class<?> appInfoClass = cl.loadClass(CLASS_APPINFO);
            sTitleField = ReflectionUtils.findField(appInfoClass, "title");
            sComponentNameField = ReflectionUtils.findField(appInfoClass, "componentName");

            sGetPackageNameMethod = ReflectionUtils.findMethod(
                    cl.loadClass("android.content.ComponentName"), "getPackageName");

            sAsAppMethod = ReflectionUtils.findMethod(
                    cl.loadClass(CLASS_ADAPTER_ITEM), "asApp", appInfoClass);

            Class<?> hotseatClass = cl.loadClass(CLASS_HOTSEAT_QSB);
            sFallbackSearchViewField = ReflectionUtils.findField(hotseatClass, "mFallbackSearchView");
            hook(ReflectionUtils.findMethod(hotseatClass, "onSearchResult", String.class, ArrayList.class))
                    .intercept(new StaleResultBlockerHooker());

            try {
                sGetTargetComponentMethod = ReflectionUtils.findMethod(
                        cl.loadClass("com.android.launcher3.model.data.ItemInfo"), "getTargetComponent");
            } catch (Throwable ignored) {
            }

            try {
                hook(ReflectionUtils.findMethod(cl.loadClass(CLASS_ITEM_CLICK_HANDLER), "onClick", View.class))
                        .intercept(new ClickLearningHooker());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Click learning hook registration failed", t);
            }

            log(Log.INFO, TAG, "Search enhancement hooks registered successfully.");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to register hooks", t);
        }
    }

    // --- Hookers ---

    private static class SearchConversionsHooker implements Hooker {
        @Override
        public Object intercept(@NonNull Chain chain) throws Throwable {
            try {
                String q = TextNormalizer.normalize((String) chain.getArg(0));
                String[] conversions = (String[]) chain.getArg(1);
                if (!TextUtils.isEmpty(q) && conversions != null && conversions.length > 0) {
                    ArrayList<String> list = new ArrayList<>(conversions.length);
                    for (String c : conversions) {
                        String n = TextNormalizer.normalize(c);
                        if (!TextUtils.isEmpty(n) && !n.equals(q)) list.add(n);
                    }
                    if (!list.isEmpty()) sQueryConversions.put(q, list);
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "SearchConversionsHooker failed", t);
            }
            return chain.proceed();
        }
    }

    private static class StaleResultBlockerHooker implements Hooker {
        @Override
        public Object intercept(@NonNull Chain chain) throws Throwable {
            try {
                Object thiz = chain.getThisObject();
                String callbackQuery = TextNormalizer.normalize((String) chain.getArg(0));
                Object editTextObj = sFallbackSearchViewField.get(thiz);
                if (editTextObj instanceof TextView tv) {
                    String current = TextNormalizer.normalize(String.valueOf(tv.getText()));
                    if (!TextUtils.isEmpty(current) && !current.equals(callbackQuery)) {
                        return null;
                    }
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "StaleResultBlockerHooker failed", t);
            }
            return chain.proceed();
        }
    }

    private static class ClickLearningHooker implements Hooker {
        @Override
        public Object intercept(@NonNull Chain chain) throws Throwable {
            try {
                View clicked = (View) chain.getArg(0);
                if (clicked == null) return chain.proceed();
                Object tag = clicked.getTag();
                if (tag == null) return chain.proceed();

                String component = getComponentFromItem(tag);
                if (TextUtils.isEmpty(component)) return chain.proceed();

                String query = extractCurrentQueryFromRoot(clicked);
                if (TextUtils.isEmpty(query)) return chain.proceed();

                String current = TextNormalizer.normalize(query);
                long now = System.currentTimeMillis();

                sLearningStore.observe(clicked.getContext(), current, component);

                String bridge = sPendingQueryStore.pollLatestUnresolvedFromOlderSession(
                        current, now, 20_000L, sSearchSessionStore.getSessionId());
                if (TextUtils.isEmpty(bridge)) {
                    bridge = QueryProcessor.pickBridgeCandidate(current,
                            sQueryHistoryStore.getRecentBefore(current, now, 15_000L, 4));
                }
                if (!TextUtils.isEmpty(bridge)) {
                    sLearningStore.observeWeakBridge(clicked.getContext(), bridge, component);
                }

                sPendingQueryStore.markResolved(current, now);
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "ClickLearningHooker failed", t);
            }
            return chain.proceed();
        }
    }

    private static class SearchAlgorithmHooker implements Hooker {
        @Override
        public Object intercept(@NonNull Chain chain) throws Throwable {
            try {
                Context context = (Context) chain.getArg(0);
                List<?> originalApps = (List<?>) chain.getArg(1);
                String rawQuery = (String) chain.getArg(2);
                String qNorm = TextNormalizer.normalize(rawQuery);
                long now = System.currentTimeMillis();

                if (TextUtils.isEmpty(qNorm)) {
                    sSearchSessionStore.onCleared(now);
                } else {
                    sSearchSessionStore.record(qNorm, now);
                    sQueryHistoryStore.record(qNorm, now);
                }

                ArrayList<Object> result = buildSearchResults(context, originalApps, rawQuery);

                if (!TextUtils.isEmpty(qNorm) && result.isEmpty()) {
                    sPendingQueryStore.recordNoResult(qNorm, now, sSearchSessionStore.getSessionId());
                }
                return result;
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "SearchAlgorithmHooker failed, fallback original", t);
            }
            return chain.proceed();
        }
    }

    // --- Search engine ---

    private static ArrayList<Object> buildSearchResults(Context context, List<?> originalApps, String rawQuery)
            throws Throwable {
        String queryNorm = TextNormalizer.normalize(rawQuery);
        ArrayList<Object> output = new ArrayList<>();
        if (TextUtils.isEmpty(queryNorm)) return output;

        LinkedHashSet<String> queryVariants = QueryProcessor.buildQueryVariants(rawQuery);
        List<String> conversions = sQueryConversions.remove(queryNorm);
        if (conversions != null) {
            for (String c : conversions) queryVariants.addAll(QueryProcessor.buildQueryVariants(c));
        }

        ArrayList<Object> candidates = new ArrayList<>();
        if (originalApps != null) candidates.addAll(originalApps);
        if (context != null && sGetGeHideAppsMethod != null) {
            Object ge = sGetGeHideAppsMethod.invoke(null, context);
            if (ge instanceof List<?> list) candidates.addAll(list);
        }

        Map<String, Integer> usageBonus = getUsageBonus(context);

        ArrayList<ScoredApp> scored = new ArrayList<>(candidates.size());
        HashSet<String> dedupe = new HashSet<>();

        for (Object app : candidates) {
            if (app == null) continue;
            String key = appKey(app);
            if (!TextUtils.isEmpty(key) && !dedupe.add(key)) continue;

            String title = getAppTitle(app);
            String pkg = getPackageName(app);

            // Cache AppForms by component key
            AppForms forms = sAppFormsCache.computeIfAbsent(
                    key != null ? key : title + "|" + pkg,
                    k -> QueryProcessor.buildAppForms(title, pkg));

            int best = 0;
            for (String q : queryVariants) {
                best = Math.max(best, AppScorer.scoreWithForms(q, forms));
            }

            String component = getComponentFromItem(app);
            if (context != null && !TextUtils.isEmpty(component)) {
                best += sLearningStore.getBonus(context, queryNorm, component);
            }

            // UsageStats bonus
            if (!TextUtils.isEmpty(pkg)) {
                best += usageBonus.getOrDefault(pkg, 0);
            }

            if (best > 0) {
                scored.add(new ScoredApp(app, best, forms.titleNorm(), forms.pkgNorm(), title, pkg));
            }
        }

        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.score(), a.score());
            if (cmp != 0) return cmp;
            cmp = a.normTitle().compareTo(b.normTitle());
            return cmp != 0 ? cmp : a.normPkg().compareTo(b.normPkg());
        });

        ArrayList<SemanticReranker.Candidate> aiCandidates = new ArrayList<>(scored.size());
        for (ScoredApp s : scored) {
            aiCandidates.add(new SemanticReranker.Candidate(s.app(), s.rawTitle(), s.rawPkg(), s.score()));
        }
        sSemanticReranker.rerank(context, rawQuery, aiCandidates);

        int count = Math.min(dynamicResultCount(aiCandidates), aiCandidates.size());
        for (int i = 0; i < count; i++) {
            output.add(sAsAppMethod.invoke(null, aiCandidates.get(i).app));
        }
        return output;
    }

    // --- Dynamic result count ---

    private static int dynamicResultCount(List<SemanticReranker.Candidate> candidates) {
        if (candidates.size() <= MIN_RESULTS) return candidates.size();

        float topScore = candidates.get(0).finalScore;
        if (topScore <= 0f) topScore = candidates.get(0).lexicalScore;

        // If top result dominates, show fewer
        if (candidates.size() >= 2) {
            float second = candidates.get(1).finalScore;
            if (second <= 0f) second = candidates.get(1).lexicalScore;
            if (topScore > 0 && second > 0 && topScore > second * 2) return MIN_RESULTS;
        }

        // If many close scores, show more
        int closeCount = 0;
        float threshold = topScore * 0.9f;
        for (SemanticReranker.Candidate c : candidates) {
            float s = c.finalScore > 0 ? c.finalScore : c.lexicalScore;
            if (s >= threshold) closeCount++;
        }
        if (closeCount > DEFAULT_MAX_RESULTS) return Math.min(closeCount, MAX_RESULTS_CAP);

        return DEFAULT_MAX_RESULTS;
    }

    // --- UsageStats ---

    @SuppressLint("QueryPermissionsNeeded")
    private static Map<String, Integer> getUsageBonus(Context context) {
        Map<String, Integer> cached = sUsageBonusCache;
        if (cached != null) return cached;

        Map<String, Integer> bonus = new HashMap<>();
        if (context == null) {
            sUsageBonusCache = bonus;
            return bonus;
        }

        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                sUsageBonusCache = bonus;
                return bonus;
            }

            long now = System.currentTimeMillis();
            long weekAgo = now - 7L * 24 * 60 * 60 * 1000L;
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, weekAgo, now);
            if (stats == null || stats.isEmpty()) {
                sUsageBonusCache = bonus;
                return bonus;
            }

            long dayAgo = now - 24L * 60 * 60 * 1000L;
            long threeDaysAgo = now - 3L * 24 * 60 * 60 * 1000L;

            for (UsageStats us : stats) {
                if (us.getTotalTimeInForeground() <= 0) continue;
                String pkg = us.getPackageName();
                long lastUsed = us.getLastTimeUsed();

                int b;
                if (lastUsed >= dayAgo) b = 80;
                else if (lastUsed >= threeDaysAgo) b = 40;
                else b = 20;

                bonus.merge(pkg, b, Math::max);
            }
        } catch (Throwable ignored) {
            // No PACKAGE_USAGE_STATS permission or other issue — graceful fallback
        }

        sUsageBonusCache = bonus;
        return bonus;
    }

    // --- Reflection accessors ---

    @SuppressLint("DiscouragedApi")
    private static String extractCurrentQueryFromRoot(View clicked) {
        try {
            Context c = clicked.getContext();
            int id = c.getResources().getIdentifier("fallback_search_view", "id", TARGET_PACKAGE);
            if (id == 0) return "";
            View root = clicked.getRootView();
            if (root == null) return "";
            View search = root.findViewById(id);
            if (search instanceof TextView tv) return String.valueOf(tv.getText());
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String getComponentFromItem(Object item) {
        try {
            if (item == null) return "";
            if (sComponentNameField != null) {
                Object cn = sComponentNameField.get(item);
                if (cn != null) return String.valueOf(cn);
            }
            if (sGetTargetComponentMethod != null) {
                Object cn = sGetTargetComponentMethod.invoke(item);
                if (cn != null) return String.valueOf(cn);
            }
        } catch (Throwable ignored) {
        }
        return "";
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
            Object cn = sComponentNameField.get(app);
            if (cn == null) return "";
            Object pkg = sGetPackageNameMethod.invoke(cn);
            return pkg == null ? "" : String.valueOf(pkg);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String appKey(Object app) {
        try {
            Object cn = sComponentNameField.get(app);
            return cn == null ? null : String.valueOf(cn);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
