package be.zvz.sony.launchersearchenhancer.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Map;

public class LearningStore {
    private static final String PREF = "xlauncher_enhanced_search_learning";
    private static final String K_COMP = "c:";
    private static final String K_CNT = "n:";
    private static final String K_TS = "t:";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long HARD_EXPIRE_MS = 30L * DAY_MS;
    private static final long GC_INTERVAL_MS = DAY_MS;
    private static final String K_LAST_GC = "__last_gc";

    private volatile SharedPreferences cachedPrefs;

    private SharedPreferences prefs(Context c) {
        SharedPreferences sp = cachedPrefs;
        if (sp != null) return sp;
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        cachedPrefs = sp;
        return sp;
    }

    public synchronized void observe(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return;
        if (queryNorm.length() < 2) return;

        long now = System.currentTimeMillis();
        maybeGc(c, now);

        SharedPreferences sp = prefs(c);
        String compKey = K_COMP + queryNorm;
        String cntKey = K_CNT + queryNorm;
        String tsKey = K_TS + queryNorm;

        // Prune if hard-expired
        long ts = sp.getLong(tsKey, 0L);
        if (ts > 0 && now - ts >= HARD_EXPIRE_MS) {
            removeMapping(sp, queryNorm);
        }

        String prevComp = sp.getString(compKey, "");
        int cnt = sp.getInt(cntKey, 0);

        cnt = component.equals(prevComp) ? Math.min(cnt + 1, 20) : 1;

        sp.edit()
                .putString(compKey, component)
                .putInt(cntKey, cnt)
                .putLong(tsKey, now)
                .commit();
    }

    public synchronized void observeWeakBridge(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return;
        if (queryNorm.length() < 2) return;

        SharedPreferences sp = prefs(c);
        String compKey = K_COMP + queryNorm;
        String cntKey = K_CNT + queryNorm;
        String tsKey = K_TS + queryNorm;

        String prevComp = sp.getString(compKey, "");
        int cnt = sp.getInt(cntKey, 0);

        cnt = component.equals(prevComp) ? Math.min(cnt + 1, 6) : 1;

        sp.edit()
                .putString(compKey, component)
                .putInt(cntKey, cnt)
                .putLong(tsKey, System.currentTimeMillis())
                .commit();
    }

    public synchronized int getBonus(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return 0;
        if (queryNorm.length() < 2) return 0;

        long now = System.currentTimeMillis();
        maybeGc(c, now);

        SharedPreferences sp = prefs(c);
        String comp = sp.getString(K_COMP + queryNorm, "");
        if (!component.equals(comp)) return 0;

        int cnt = sp.getInt(K_CNT + queryNorm, 0);
        long ts = sp.getLong(K_TS + queryNorm, 0L);
        if (ts <= 0L) return 0;

        long age = now - ts;
        if (age >= HARD_EXPIRE_MS) {
            removeMapping(sp, queryNorm);
            return 0;
        }

        // Frecency: base score from count, multiplied by recency factor
        int base = Math.min(110 + cnt * 12, 240);
        float recency = recencyMultiplier(age);
        return Math.max((int) (base * recency), 0);
    }

    private static float recencyMultiplier(long ageMs) {
        if (ageMs < DAY_MS) return 1.0f;
        if (ageMs < 3 * DAY_MS) return 0.8f;
        if (ageMs < 7 * DAY_MS) return 0.6f;
        if (ageMs < 14 * DAY_MS) return 0.4f;
        if (ageMs < 30 * DAY_MS) return 0.2f;
        return 0f;
    }

    private void removeMapping(SharedPreferences sp, String queryNorm) {
        sp.edit()
                .remove(K_COMP + queryNorm)
                .remove(K_CNT + queryNorm)
                .remove(K_TS + queryNorm)
                .apply();
    }

    private void maybeGc(Context c, long now) {
        SharedPreferences sp = prefs(c);
        long last = sp.getLong(K_LAST_GC, 0L);
        if (now - last < GC_INTERVAL_MS) return;

        Map<String, ?> all = sp.getAll();
        if (all == null || all.isEmpty()) {
            sp.edit().putLong(K_LAST_GC, now).apply();
            return;
        }
        SharedPreferences.Editor ed = sp.edit();

        for (String k : all.keySet()) {
            if (!k.startsWith(K_TS)) continue;
            Object v = all.get(k);
            if (!(v instanceof Long ts)) continue;
            if (now - ts < HARD_EXPIRE_MS) continue;

            String q = k.substring(K_TS.length());
            ed.remove(K_COMP + q).remove(K_CNT + q).remove(K_TS + q);
        }

        ed.putLong(K_LAST_GC, now).apply();
    }
}
