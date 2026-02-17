package be.zvz.sony.launchersearchenhancer.reranker;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class SemanticReranker {

    private static final String CACHE_VERSION = "v2";
    private static final String MODULE_PACKAGE = "be.zvz.sony.launchersearchenhancer";

    private static final String MODEL_FILE = "model_qint8_arm64.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";

    private static final String ASSET_MODEL = "semantic/model_qint8_arm64.onnx";
    private static final String ASSET_TOKENIZER = "semantic/tokenizer.json";

    private static final int MAX_SEQ_LEN = 48;
    private static final int RERANK_TOP_N = 32;
    private static final int EMBED_CACHE_MAX = 1024;

    private final ConcurrentHashMap<String, float[]> embedCache = new ConcurrentHashMap<>();

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile JsonTokenizer tokenizer;

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
        if (context == null || TextUtils.isEmpty(query) || candidates == null || candidates.size() <= 1) {
            return;
        }

        try {
            ensureReady(context);

            int top = Math.min(RERANK_TOP_N, candidates.size());
            List<Candidate> head = new ArrayList<>(candidates.subList(0, top));
            List<Candidate> tail = top < candidates.size()
                    ? new ArrayList<>(candidates.subList(top, candidates.size()))
                    : Collections.emptyList();

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
            if (embedCache.size() > EMBED_CACHE_MAX) {
                embedCache.clear();
            }
            embedCache.put(key, v);
        }
        return v;
    }

    private void ensureReady(Context hostContext) throws Exception {
        if (session != null && tokenizer != null) return;

        synchronized (initLock) {
            if (session != null && tokenizer != null) return;

            if (!isArm64()) {
                throw new IllegalStateException("model_qint8_arm64.onnx requires arm64-v8a");
            }

            File dir = new File(hostContext.getFilesDir(), "semantic_cache_" + CACHE_VERSION);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create cache dir: " + dir.getAbsolutePath());
            }

            File modelFile = new File(dir, MODEL_FILE);
            File tokenizerFile = new File(dir, TOKENIZER_FILE);

            Context moduleContext = hostContext.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );

            ensureAssetCopied(moduleContext, ASSET_MODEL, modelFile);
            ensureAssetCopied(moduleContext, ASSET_TOKENIZER, tokenizerFile);

            tokenizer = JsonTokenizer.fromTokenizerJson(tokenizerFile);

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            session = env.createSession(modelFile.getAbsolutePath(), opts);
        }
    }

    private boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private void ensureAssetCopied(Context moduleContext, String assetPath, File outFile) throws Exception {
        if (outFile.exists() && outFile.length() > 0) return;

        File tmp = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
        try (InputStream in = moduleContext.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        }

        if (tmp.length() <= 0) {
            tmp.delete();
            throw new IllegalStateException("Asset copy failed: " + assetPath);
        }

        if (outFile.exists() && !outFile.delete()) {
            throw new IllegalStateException("Failed deleting old file: " + outFile.getAbsolutePath());
        }
        if (!tmp.renameTo(outFile)) {
            throw new IllegalStateException("Failed to move temp file: " + outFile.getAbsolutePath());
        }
    }

    private float[] embed(String text) throws Exception {
        if (session == null || tokenizer == null || env == null) return null;

        JsonTokenizer.Encoded e = tokenizer.encode(text, MAX_SEQ_LEN);

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

                if (out instanceof float[][] v) {
                    if (v.length == 0) return null;
                    return l2norm(v[0]);
                }

                if (out instanceof float[][][] tokenEmb) {
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
        return Math.min(v, 1f);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static final class JsonTokenizer {

        interface Impl {
            Encoded encode(String text, int maxLen);
        }

        private final Impl impl;

        private JsonTokenizer(Impl impl) {
            this.impl = impl;
        }

        static JsonTokenizer fromTokenizerJson(File tokenizerJsonFile) throws Exception {
            String json = readAll(tokenizerJsonFile);
            JSONObject root = new JSONObject(json);

            JSONObject model = root.getJSONObject("model");
            String modelType = model.optString("type", "");

            boolean lowercase = false;
            JSONObject normalizer = root.optJSONObject("normalizer");
            if (normalizer != null) {
                lowercase = containsLowercaseFlag(normalizer);
            }

            if ("WordPiece".equalsIgnoreCase(modelType)) {
                return new JsonTokenizer(new WordPieceImpl(root, model, lowercase));
            }

            if ("Unigram".equalsIgnoreCase(modelType)) {
                return new JsonTokenizer(new UnigramImpl(root, model, lowercase));
            }

            throw new IllegalStateException("Unsupported tokenizer model.type=" + modelType);
        }

        Encoded encode(String text, int maxLen) {
            return impl.encode(text, maxLen);
        }

        private static String readAll(File f) throws Exception {
            StringBuilder sb = new StringBuilder((int) Math.max(1024, f.length()));
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        }

        private static boolean containsLowercaseFlag(JSONObject obj) {
            if (obj == null) return false;
            if (obj.has("lowercase") && obj.optBoolean("lowercase", false)) return true;

            String type = obj.optString("type", "");
            if ("Sequence".equalsIgnoreCase(type)) {
                JSONArray arr = obj.optJSONArray("normalizers");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject n = arr.optJSONObject(i);
                        if (containsLowercaseFlag(n)) return true;
                    }
                }
            }
            return false;
        }

        private static int pickSpecialId(JSONObject root, Map<String, Integer> vocab, String primary, String alt, int fallback) {
            Integer fromVocab = vocab.get(primary);
            if (fromVocab != null) return fromVocab;
            Integer fromAlt = vocab.get(alt);
            if (fromAlt != null) return fromAlt;

            JSONArray added = root.optJSONArray("added_tokens");
            if (added != null) {
                for (int i = 0; i < added.length(); i++) {
                    JSONObject o = added.optJSONObject(i);
                    if (o == null) continue;
                    String content = o.optString("content", "");
                    int id = o.optInt("id", -1);
                    if (id >= 0 && (primary.equals(content) || alt.equals(content))) {
                        return id;
                    }
                }
            }
            return fallback;
        }

        private static String normalizeText(String s, boolean lowercase) {
            if (s == null) return "";
            String out = Normalizer.normalize(s, Normalizer.Form.NFKC).trim();
            if (lowercase) out = out.toLowerCase(Locale.ROOT);
            return out;
        }

        private static void fill(List<Integer> ids, long[] inputIds, long[] attentionMask, int maxLen, int padId) {
            int n = Math.min(ids.size(), maxLen);
            for (int i = 0; i < n; i++) {
                inputIds[i] = ids.get(i);
                attentionMask[i] = 1;
            }
            for (int i = n; i < maxLen; i++) {
                inputIds[i] = padId;
                attentionMask[i] = 0;
            }
        }

        record Encoded(long[] inputIds, long[] attentionMask) {
        }

        private static final class WordPieceImpl implements Impl {
            private final Map<String, Integer> vocab;
            private final int clsId;
            private final int sepId;
            private final int unkId;
            private final int padId;
            private final boolean lowercase;
            private final int maxInputCharsPerWord;

            WordPieceImpl(JSONObject root, JSONObject model, boolean lowercase) throws Exception {
                JSONObject vocabObj = model.getJSONObject("vocab");
                Map<String, Integer> v = new HashMap<>(vocabObj.length() * 2);
                JSONArray names = vocabObj.names();
                if (names == null) throw new IllegalStateException("tokenizer vocab empty");
                for (int i = 0; i < names.length(); i++) {
                    String token = names.getString(i);
                    v.put(token, vocabObj.getInt(token));
                }
                this.vocab = v;
                this.lowercase = lowercase;
                this.maxInputCharsPerWord = model.optInt("max_input_chars_per_word", 100);

                String unkToken = model.optString("unk_token", "[UNK]");
                this.clsId = pickSpecialId(root, vocab, "[CLS]", "<s>", 101);
                this.sepId = pickSpecialId(root, vocab, "[SEP]", "</s>", 102);
                this.padId = pickSpecialId(root, vocab, "[PAD]", "<pad>", 0);
                this.unkId = pickSpecialId(root, vocab, unkToken, "<unk>", 100);
            }

            @Override
            public Encoded encode(String text, int maxLen) {
                long[] inputIds = new long[maxLen];
                long[] attentionMask = new long[maxLen];

                List<Integer> ids = new ArrayList<>(maxLen);
                ids.add(clsId);

                String norm = normalizeText(text, lowercase);
                for (String tok : basicTokenize(norm)) {
                    List<Integer> wp = wordPiece(tok);
                    for (Integer id : wp) {
                        if (ids.size() >= maxLen - 1) break;
                        ids.add(id);
                    }
                    if (ids.size() >= maxLen - 1) break;
                }

                ids.add(sepId);
                fill(ids, inputIds, attentionMask, maxLen, padId);
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
                if (cur.length() > 0) {
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
                if (token.length() > maxInputCharsPerWord) return Collections.singletonList(unkId);

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
        }

        private static final class UnigramImpl implements Impl {
            private static final char SPIECE_WS = '\u2581';

            private final Map<String, Integer> pieceToId;
            private final Map<String, Float> pieceScore;
            private final int clsId;
            private final int sepId;
            private final int unkId;
            private final int padId;
            private final boolean lowercase;
            private final int maxPieceLen;

            UnigramImpl(JSONObject root, JSONObject model, boolean lowercase) throws Exception {
                JSONArray vocabArr = model.getJSONArray("vocab");
                this.pieceToId = new HashMap<>(vocabArr.length() * 2);
                this.pieceScore = new HashMap<>(vocabArr.length() * 2);
                int maxLen = 1;

                for (int i = 0; i < vocabArr.length(); i++) {
                    JSONArray item = vocabArr.getJSONArray(i);
                    String piece = item.getString(0);
                    float score = (float) item.getDouble(1);
                    pieceToId.put(piece, i);
                    pieceScore.put(piece, score);
                    if (piece.length() > maxLen) maxLen = piece.length();
                }
                this.maxPieceLen = maxLen;
                this.lowercase = lowercase;

                int unkIdxFromModel = model.optInt("unk_id", -1);
                this.unkId = unkIdxFromModel >= 0 ? unkIdxFromModel : pickSpecialId(root, pieceToId, "[UNK]", "<unk>", 0);
                this.clsId = pickSpecialId(root, pieceToId, "[CLS]", "<s>", 1);
                this.sepId = pickSpecialId(root, pieceToId, "[SEP]", "</s>", 2);
                this.padId = pickSpecialId(root, pieceToId, "[PAD]", "<pad>", 0);
            }

            @Override
            public Encoded encode(String text, int maxLen) {
                long[] inputIds = new long[maxLen];
                long[] attentionMask = new long[maxLen];

                List<Integer> ids = new ArrayList<>(maxLen);
                ids.add(clsId);

                String norm = normalizeText(text, lowercase);
                String sp = toSentencePieceLike(norm);
                List<Integer> toks = unigramTokenize(sp);

                for (Integer id : toks) {
                    if (ids.size() >= maxLen - 1) break;
                    ids.add(id);
                }

                ids.add(sepId);
                fill(ids, inputIds, attentionMask, maxLen, padId);
                return new Encoded(inputIds, attentionMask);
            }

            private String toSentencePieceLike(String s) {
                if (TextUtils.isEmpty(s)) return "";
                String collapsed = s.replaceAll("\\s+", " ").trim();
                if (collapsed.isEmpty()) return "";
                return SPIECE_WS + collapsed.replace(' ', SPIECE_WS);
            }

            private List<Integer> unigramTokenize(String input) {
                if (input.isEmpty()) return Collections.emptyList();

                int n = input.length();
                float[] best = new float[n + 1];
                int[] prev = new int[n + 1];
                String[] pieceAt = new String[n + 1];

                for (int i = 0; i <= n; i++) {
                    best[i] = Float.NEGATIVE_INFINITY;
                    prev[i] = -1;
                }
                best[0] = 0f;

                for (int i = 0; i < n; i++) {
                    if (best[i] == Float.NEGATIVE_INFINITY) continue;

                    int endMax = Math.min(n, i + maxPieceLen);
                    boolean matched = false;

                    for (int j = i + 1; j <= endMax; j++) {
                        String sub = input.substring(i, j);
                        Integer id = pieceToId.get(sub);
                        if (id == null) continue;
                        matched = true;
                        float score = pieceScore.getOrDefault(sub, -10f);
                        float cand = best[i] + score;
                        if (cand > best[j]) {
                            best[j] = cand;
                            prev[j] = i;
                            pieceAt[j] = sub;
                        }
                    }

                    if (!matched) {
                        int j = i + 1;
                        float cand = best[i] - 100f;
                        if (cand > best[j]) {
                            best[j] = cand;
                            prev[j] = i;
                            pieceAt[j] = null;
                        }
                    }
                }

                ArrayList<Integer> rev = new ArrayList<>();
                int pos = n;
                while (pos > 0) {
                    int p = prev[pos];
                    if (p < 0) {
                        rev.add(unkId);
                        pos--;
                        continue;
                    }
                    String piece = pieceAt[pos];
                    if (piece == null) {
                        rev.add(unkId);
                    } else {
                        Integer id = pieceToId.get(piece);
                        rev.add(id == null ? unkId : id);
                    }
                    pos = p;
                }
                Collections.reverse(rev);
                return rev;
            }
        }
    }
}
