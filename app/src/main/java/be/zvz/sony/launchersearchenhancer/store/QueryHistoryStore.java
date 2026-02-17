package be.zvz.sony.launchersearchenhancer.store;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class QueryHistoryStore {
    private static final int MAX = 12;
    private final ArrayList<Entry> entries = new ArrayList<>();

    public synchronized void record(String queryNorm, long ts) {
        if (TextUtils.isEmpty(queryNorm) || queryNorm.length() < 2) return;

        if (!entries.isEmpty()) {
            Entry last = entries.get(entries.size() - 1);

            if (last.q.equals(queryNorm)) {
                last.ts = ts;
                return;
            }

            if (queryNorm.startsWith(last.q) && ts - last.ts < 2500L) {
                last.q = queryNorm;
                last.ts = ts;
                return;
            }
        }

        entries.add(new Entry(queryNorm, ts));
        if (entries.size() > MAX) entries.remove(0);
    }


    public synchronized List<String> getRecentBefore(String current, long now, long windowMs, int limit) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = entries.get(i);
            if (now - e.ts > windowMs) break;
            if (e.q.equals(current)) continue;
            if (e.q.length() < 2) continue;
            out.add(e.q);
        }
        return out;
    }

    public synchronized boolean isStable(String q, long now, long minAgeMs) {
        if (TextUtils.isEmpty(q) || entries.isEmpty()) return false;
        Entry last = entries.get(entries.size() - 1);
        return q.equals(last.q) && (now - last.ts) >= minAgeMs;
    }

    private static final class Entry {
        String q;
        long ts;
        Entry(String q, long ts) {
            this.q = q;
            this.ts = ts;
        }
    }
}
