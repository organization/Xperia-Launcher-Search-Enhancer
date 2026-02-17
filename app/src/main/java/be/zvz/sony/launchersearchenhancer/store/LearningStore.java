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

    private static final long SOFT_EXPIRE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final long HARD_EXPIRE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long GC_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final String K_LAST_GC = "__last_gc";

    public void observe(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return;
        if (queryNorm.length() < 2) return;

        long now = System.currentTimeMillis();
        maybeGc(c, now);
        pruneOne(c, queryNorm, now);

        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String compKey = K_COMP + queryNorm;
        String cntKey = K_CNT + queryNorm;
        String tsKey = K_TS + queryNorm;

        String prevComp = sp.getString(compKey, "");
        int cnt = sp.getInt(cntKey, 0);

        if (component.equals(prevComp)) {
            cnt = Math.min(cnt + 1, 20);
        } else {
            cnt = 1;
        }

        sp.edit()
                .putString(compKey, component)
                .putInt(cntKey, cnt)
                .putLong(tsKey, now)
                .apply();
    }

    public void observeWeakBridge(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return;
        if (queryNorm.length() < 2) return;

        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String compKey = K_COMP + queryNorm;
        String cntKey = K_CNT + queryNorm;
        String tsKey = K_TS + queryNorm;

        String prevComp = sp.getString(compKey, "");
        int cnt = sp.getInt(cntKey, 0);

        if (component.equals(prevComp)) {
            cnt = Math.min(cnt + 1, 6);
        } else {
            cnt = 1;
        }

        sp.edit()
                .putString(compKey, component)
                .putInt(cntKey, cnt)
                .putLong(tsKey, System.currentTimeMillis())
                .apply();
    }

    public int getBonus(Context c, String queryNorm, String component) {
        if (c == null || TextUtils.isEmpty(queryNorm) || TextUtils.isEmpty(component)) return 0;
        if (queryNorm.length() < 2) return 0;

        long now = System.currentTimeMillis();
        maybeGc(c, now);

        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
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

        int base = Math.min(110 + cnt * 12, 240);

        if (age > SOFT_EXPIRE_MS) {
            long decayAge = age - SOFT_EXPIRE_MS;
            long steps = decayAge / (7L * 24L * 60L * 60L * 1000L);
            for (long i = 0; i <= steps; i++) {
                base /= 2;
                if (base <= 0) return 0;
            }
        }

        return Math.max(base, 0);
    }

    private void removeMapping(SharedPreferences sp, String queryNorm) {
        sp.edit()
                .remove(K_COMP + queryNorm)
                .remove(K_CNT + queryNorm)
                .remove(K_TS + queryNorm)
                .apply();
    }

    private void pruneOne(Context c, String queryNorm, long now) {
        if (TextUtils.isEmpty(queryNorm)) return;
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long ts = sp.getLong(K_TS + queryNorm, 0L);
        if (ts <= 0L) return;
        if (now - ts >= HARD_EXPIRE_MS) {
            removeMapping(sp, queryNorm);
        }
    }

    private void maybeGc(Context c, long now) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = sp.getLong(K_LAST_GC, 0L);
        if (now - last < GC_INTERVAL_MS) return;

        Map<String, ?> all = sp.getAll();
        SharedPreferences.Editor ed = sp.edit();

        for (String k : all.keySet()) {
            if (!k.startsWith(K_TS)) continue;
            Object v = all.get(k);
            if (!(v instanceof Long)) continue;

            long ts = (Long) v;
            if (now - ts < HARD_EXPIRE_MS) continue;

            String q = k.substring(K_TS.length());
            ed.remove(K_COMP + q);
            ed.remove(K_CNT + q);
            ed.remove(K_TS + q);
        }

        ed.putLong(K_LAST_GC, now).apply();
    }
}
