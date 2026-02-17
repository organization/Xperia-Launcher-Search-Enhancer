package be.zvz.sony.launchersearchenhancer.store;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PendingQueryStore {
    private static final int MAX = 20;
    private final ArrayList<Entry> entries = new ArrayList<>();

    public synchronized void recordNoResult(String q, long ts) {
        if (TextUtils.isEmpty(q) || q.length() < 2) return;
        entries.add(new Entry(q, ts, false));
        if (entries.size() > MAX) entries.remove(0);
    }

    public synchronized void markResolved(String q, long ts) {
        if (TextUtils.isEmpty(q)) return;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.q.equals(q)) {
                e.resolved = true;
                e.ts = ts;
                return;
            }
        }
    }

    public synchronized List<String> getRecentNoResultBefore(String current, long now, long windowMs, int limit) {
        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = entries.get(i);
            if (now - e.ts > windowMs) break;
            if (e.resolved) continue;
            if (e.q.equals(current)) continue;
            if (e.q.length() < 2) continue;
            if (seen.add(e.q)) out.add(e.q);
        }
        return out;
    }

    private static final class Entry {
        final String q;
        long ts;
        boolean resolved;
        Entry(String q, long ts, boolean resolved) {
            this.q = q;
            this.ts = ts;
            this.resolved = resolved;
        }
    }
}
