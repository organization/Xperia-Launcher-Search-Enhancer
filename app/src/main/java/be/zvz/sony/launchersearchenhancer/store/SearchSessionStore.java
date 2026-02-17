package be.zvz.sony.launchersearchenhancer.store;

public class SearchSessionStore {
    private int sessionId = 1;
    private String lastQuery = "";
    private long lastTs = 0L;

    public synchronized void onCleared(long ts) {
        sessionId++;
        lastQuery = "";
        lastTs = ts;
    }

    public synchronized void record(String q, long ts) {
        lastQuery = q;
        lastTs = ts;
    }

    public synchronized int getSessionId() {
        return sessionId;
    }
}
