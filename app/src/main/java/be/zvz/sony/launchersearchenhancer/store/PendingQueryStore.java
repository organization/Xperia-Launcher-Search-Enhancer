package be.zvz.sony.launchersearchenhancer.store;

import android.text.TextUtils;

import java.util.ArrayList;

public class PendingQueryStore {
    private static final int MAX = 30;
    private static final long DUP_COOLDOWN_MS = 800L;
    private final ArrayList<Entry> entries = new ArrayList<>();

    public synchronized void recordNoResult(String q, long ts, int sessionId) {
        if (TextUtils.isEmpty(q) || q.length() < 2) return;

        if (!entries.isEmpty()) {
            Entry last = entries.get(entries.size() - 1);
            if (last.q.equals(q) && (ts - last.ts) < DUP_COOLDOWN_MS) {
                return;
            }
        }

        entries.add(new Entry(q, ts, sessionId, false));
        if (entries.size() > MAX) entries.remove(0);
    }

    public synchronized void markResolved(String q, long ts) {
        if (TextUtils.isEmpty(q)) return;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.q.equals(q) && !e.resolved) {
                e.resolved = true;
                return;
            }
        }
    }

    public synchronized String pollLatestUnresolvedFromOlderSession(String current, long now, long windowMs, int currentSession) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (now - e.ts > windowMs) break;
            if (e.resolved) continue;
            if (e.sessionId >= currentSession) continue;
            if (e.q.equals(current)) continue;
            e.resolved = true;
            return e.q;
        }
        return "";
    }

    private static final class Entry {
        final String q;
        final long ts;
        final int sessionId;
        boolean resolved;

        Entry(String q, long ts, int sessionId, boolean resolved) {
            this.q = q;
            this.ts = ts;
            this.sessionId = sessionId;
            this.resolved = resolved;
        }
    }
}
