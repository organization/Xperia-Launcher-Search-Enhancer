package be.zvz.sony.launchersearchenhancer.reranker;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SemanticReranker {

    private static final String CACHE_VERSION = "v1";
    private static final String MODEL_URL =
            "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model.onnx";
    private static final String VOCAB_URL =
            "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/vocab.txt";

    private static final String MODEL_FILE = "model.onnx";
    private static final String VOCAB_FILE = "vocab.txt";

    private static final int MAX_SEQ_LEN = 48;
    private static final int RERANK_TOP_N = 32;
    private static final int EMBED_CACHE_MAX = 1024;

    private final OkHttpClient http = new OkHttpClient();
    private final ConcurrentHashMap<String, float[]> embedCache = new ConcurrentHashMap<>();

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile WordPieceTokenizer tokenizer;

    private final Object initLock = new Object();

    public static final class Candidate {
        public final Object app;
        public final String title;
        public final String packageName;
        public final int lexicalScore;
        public float semanticScore;
        public float finalScore;

        public Candidate(Object app, String title, String packageName, int lexicalScore) {
            this.app = app;
            this.title = title == null ? "" : title;
            this.packageName = packageName == null ? "" : packageName;
            this.lexicalScore = lexicalScore;
        }
    }

    public void rerank(Context context, String query, List<Candidate> candidates) {
        if (context == null || TextUtils.isEmpty(query) || candidates == null || candidates.size() <= 1) return;

        try {
            ensureReady(context);

            int top = Math.min(RERANK_TOP_N, candidates.size());
            List<Candidate> head = new ArrayList<>(candidates.subList(0, top));
            List<Candidate> tail = top < candidates.size()
                    ? new ArrayList<>(candidates.subList(top, candidates.size()))
                    : Collections.<Candidate>emptyList();

            float[] qVec = embedCached("q|" + normalize(query), query);
            if (qVec == null) return;

            float wSemantic = semanticWeight(query);
            float wLexical = 1f - wSemantic;

            for (Candidate c : head) {
                String appText = buildAppText(c.title, c.packageName);
                float[] aVec = embedCached("a|" + normalize(appText), appText);
                c.semanticScore = aVec == null ? 0f : cosine(qVec, aVec);

                float lexicalNorm = clamp01(c.lexicalScore / 1300f);
                float semanticNorm = clamp01((c.semanticScore + 1f) * 0.5f);
                c.finalScore = wLexical * lexicalNorm + wSemantic * semanticNorm;
            }

            head.sort((o1, o2) -> {
                int f = Float.compare(o2.finalScore, o1.finalScore);
                if (f != 0) return f;
                int l = Integer.compare(o2.lexicalScore, o1.lexicalScore);
                if (l != 0) return l;
                return o1.title.compareToIgnoreCase(o2.title);
            });

            candidates.clear();
            candidates.addAll(head);
            candidates.addAll(tail);
        } catch (Throwable ignored) {
        }
    }

    private float semanticWeight(String q) {
        int len = q == null ? 0 : q.trim().length();
        if (len <= 2) return 0.10f;
        if (len <= 4) return 0.20f;
        return 0.35f;
    }

    private String buildAppText(String title, String pkg) {
        String pkgTokens = pkg == null ? "" : pkg.replace('.', ' ').replace('_', ' ').replace('-', ' ');
        return (title == null ? "" : title) + " " + pkgTokens;
    }

    private float[] embedCached(String key, String text) throws Exception {
        float[] c = embedCache.get(key);
        if (c != null) return c;

        float[] v = embed(text);
        if (v != null) {
            if (embedCache.size() > EMBED_CACHE_MAX) embedCache.clear();
            embedCache.put(key, v);
        }
        return v;
    }

    private void ensureReady(Context context) throws Exception {
        if (session != null && tokenizer != null) return;

        synchronized (initLock) {
            if (session != null && tokenizer != null) return;

            File dir = new File(context.getFilesDir(), "semantic_cache_" + CACHE_VERSION);
            if (!dir.exists()) dir.mkdirs();

            File modelFile = new File(dir, MODEL_FILE);
            File vocabFile = new File(dir, VOCAB_FILE);

            ensureDownloaded(MODEL_URL, modelFile);
            ensureDownloaded(VOCAB_URL, vocabFile);

            tokenizer = WordPieceTokenizer.fromVocabFile(vocabFile);

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            session = env.createSession(modelFile.getAbsolutePath(), opts);
        }
    }

    private void ensureDownloaded(String url, File outFile) throws Exception {
        if (outFile.exists() && outFile.length() > 0) return;

        File tmp = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
        Request req = new Request.Builder().url(url).build();

        try (Response rsp = http.newCall(req).execute()) {
            if (!rsp.isSuccessful() || rsp.body() == null) {
                throw new IllegalStateException("Download failed: " + url);
            }
            try (InputStream in = rsp.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
            }
        }

        if (tmp.length() <= 0) {
            tmp.delete();
            throw new IllegalStateException("Downloaded empty file");
        }

        if (outFile.exists()) outFile.delete();
        if (!tmp.renameTo(outFile)) {
            throw new IllegalStateException("Failed to move temp file");
        }
    }

    private float[] embed(String text) throws Exception {
        if (session == null || tokenizer == null || env == null) return null;

        WordPieceTokenizer.Encoded e = tokenizer.encode(text, MAX_SEQ_LEN);

        long[][] inputIds = new long[][]{e.inputIds};
        long[][] attentionMask = new long[][]{e.attentionMask};
        long[][] tokenTypeIds = new long[][]{new long[MAX_SEQ_LEN]};

        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor tMask = OnnxTensor.createTensor(env, attentionMask);
             OnnxTensor tType = OnnxTensor.createTensor(env, tokenTypeIds)) {

            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", tType);
            }

            try (OrtSession.Result r = session.run(inputs)) {
                Object out = r.get(0).getValue();

                if (out instanceof float[][]) {
                    float[][] v = (float[][]) out;
                    if (v.length == 0) return null;
                    return l2norm(v[0]);
                }

                if (out instanceof float[][][]) {
                    float[][][] tokenEmb = (float[][][]) out;
                    return l2norm(meanPool(tokenEmb[0], e.attentionMask));
                }
            }
        }

        return null;
    }

    private float[] meanPool(float[][] tokenEmb, long[] mask) {
        if (tokenEmb == null || tokenEmb.length == 0) return null;
        int dim = tokenEmb[0].length;
        float[] sum = new float[dim];
        float count = 0f;

        int len = Math.min(tokenEmb.length, mask.length);
        for (int i = 0; i < len; i++) {
            if (mask[i] == 0) continue;
            for (int d = 0; d < dim; d++) sum[d] += tokenEmb[i][d];
            count += 1f;
        }

        if (count <= 0f) return sum;
        for (int d = 0; d < dim; d++) sum[d] /= count;
        return sum;
    }

    private float[] l2norm(float[] v) {
        if (v == null) return null;
        double s = 0d;
        for (float x : v) s += x * x;
        if (s <= 0d) return v;
        float inv = (float) (1d / Math.sqrt(s));
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] * inv;
        return out;
    }

    private float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0f;
        double d = 0d;
        for (int i = 0; i < n; i++) d += a[i] * b[i];
        return (float) d;
    }

    private float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    private static final class WordPieceTokenizer {
        private final Map<String, Integer> vocab;
        private final int clsId;
        private final int sepId;
        private final int unkId;
        private final int padId;

        private WordPieceTokenizer(Map<String, Integer> vocab) {
            this.vocab = vocab;
            this.clsId = idOf("[CLS]", 101);
            this.sepId = idOf("[SEP]", 102);
            this.unkId = idOf("[UNK]", 100);
            this.padId = idOf("[PAD]", 0);
        }

        static WordPieceTokenizer fromVocabFile(File vocabFile) throws Exception {
            Map<String, Integer> map = new LinkedHashMap<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(vocabFile), StandardCharsets.UTF_8))) {
                String line;
                int idx = 0;
                while ((line = br.readLine()) != null) {
                    map.put(line, idx++);
                }
            }
            return new WordPieceTokenizer(map);
        }

        Encoded encode(String text, int maxLen) {
            long[] inputIds = new long[maxLen];
            long[] attentionMask = new long[maxLen];

            List<Integer> ids = new ArrayList<>(maxLen);
            ids.add(clsId);

            String norm = normalizeText(text);
            for (String tok : basicTokenize(norm)) {
                List<Integer> wp = wordPiece(tok);
                for (Integer id : wp) {
                    if (ids.size() >= maxLen - 1) break;
                    ids.add(id);
                }
                if (ids.size() >= maxLen - 1) break;
            }

            ids.add(sepId);

            int n = Math.min(ids.size(), maxLen);
            for (int i = 0; i < n; i++) {
                inputIds[i] = ids.get(i);
                attentionMask[i] = 1;
            }
            for (int i = n; i < maxLen; i++) {
                inputIds[i] = padId;
                attentionMask[i] = 0;
            }

            return new Encoded(inputIds, attentionMask);
        }

        private List<String> basicTokenize(String s) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    flush(cur, out);
                    continue;
                }
                if (isDelimiter(c)) {
                    flush(cur, out);
                    out.add(String.valueOf(c));
                    continue;
                }
                cur.append(c);
            }
            flush(cur, out);
            return out;
        }

        private void flush(StringBuilder cur, List<String> out) {
            if (!TextUtils.isEmpty(cur)) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }

        private boolean isDelimiter(char c) {
            return Character.isISOControl(c) || Character.isSpaceChar(c)
                    || ".,!?;:()[]{}\"'`~@#$%^&*+-=/\\|_<>".indexOf(c) >= 0;
        }

        private List<Integer> wordPiece(String token) {
            if (token.isEmpty()) return Collections.emptyList();

            List<Integer> ids = new ArrayList<>();
            int start = 0;
            boolean bad = false;

            while (start < token.length()) {
                int end = token.length();
                Integer curId = null;

                while (start < end) {
                    String sub = token.substring(start, end);
                    if (start > 0) sub = "##" + sub;
                    Integer id = vocab.get(sub);
                    if (id != null) {
                        curId = id;
                        break;
                    }
                    end--;
                }

                if (curId == null) {
                    bad = true;
                    break;
                }

                ids.add(curId);
                start = end;
            }

            if (bad) {
                ids.clear();
                ids.add(unkId);
            }

            return ids;
        }

        private int idOf(String token, int fallback) {
            Integer id = vocab.get(token);
            return id != null ? id : fallback;
        }

        private String normalizeText(String s) {
            if (s == null) return "";
            return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        }

        record Encoded(long[] inputIds, long[] attentionMask) {
        }
    }
}