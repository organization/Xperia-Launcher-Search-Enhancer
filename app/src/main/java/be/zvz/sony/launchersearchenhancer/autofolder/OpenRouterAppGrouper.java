package be.zvz.sony.launchersearchenhancer.autofolder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import be.zvz.sony.launchersearchenhancer.autofolder.SemanticAppGrouper.AppCandidate;
import be.zvz.sony.launchersearchenhancer.autofolder.SemanticAppGrouper.Group;
import be.zvz.sony.launchersearchenhancer.openrouter.OpenRouterProxyService;

public final class OpenRouterAppGrouper {

    private static final String MODULE_PACKAGE = "be.zvz.sony.launchersearchenhancer";
    private static final String PROXY_SERVICE_CLASS =
            MODULE_PACKAGE + ".openrouter.OpenRouterProxyService";
    private static final long REQUEST_TIMEOUT_SECONDS = 95;
    private static final int MAX_PROMPT_CHARS = 4000;
    private static final int MAX_LABEL_CHARS = 24;

    public List<Group> group(Context context, List<AppCandidate> apps, Config config)
            throws Exception {
        if (context == null || apps == null || apps.size() < 2 || config == null) {
            return Collections.emptyList();
        }

        String responseBody = requestWithJsonModeFallback(
                context,
                config.apiKey,
                buildGroupingRequest(apps, config, true),
                buildGroupingRequest(apps, config, false));
        String content = completionContent(responseBody);
        try {
            return parseGroups(content, apps);
        } catch (IOException | JSONException parseError) {
            String repairedResponse = requestWithJsonModeFallback(
                    context,
                    config.apiKey,
                    buildRepairRequest(config, content, parseError, true),
                    buildRepairRequest(config, content, parseError, false));
            return parseGroups(completionContent(repairedResponse), apps);
        }
    }

    private JSONObject buildGroupingRequest(
            List<AppCandidate> apps,
            Config config,
            boolean requestJsonMode
    ) throws JSONException {
        JSONArray appItems = new JSONArray();
        for (int i = 0; i < apps.size(); i++) {
            AppCandidate app = apps.get(i);
            appItems.put(new JSONObject()
                    .put("id", i)
                    .put("title", app.title)
                    .put("package", app.packageName));
        }

        String prompt = trimPrompt(config.customPrompt);
        String userContent = "Custom grouping conditions:\n"
                + (TextUtils.isEmpty(prompt) ? "No extra conditions." : prompt)
                + "\n\nApps to organize:\n"
                + appItems.toString(2);

        JSONArray messages = new JSONArray()
                .put(message("system", systemPrompt()))
                .put(message("user", userContent));

        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("temperature", 0.1)
                .put("max_tokens", 1800);
        if (requestJsonMode) {
            request.put("response_format", new JSONObject().put("type", "json_object"));
        }
        return request;
    }

    private static JSONObject message(String role, String content) throws JSONException {
        return new JSONObject()
                .put("role", role)
                .put("content", content);
    }

    private static boolean isResponseFormatFailure(IOException e) {
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("response_format")
                || lower.contains("json mode")
                || lower.contains("structured output");
    }

    private static String systemPrompt() {
        return "You organize Android launcher app tray items into folders. "
                + "CRITICAL OUTPUT CONTRACT, especially for Claude and Gemini models: "
                + "your entire reply must be exactly one valid JSON object. "
                + "Do not write Markdown, code fences, commentary, apologies, reasoning, or trailing text. "
                + "Start with { and end with }. "
                + "Use this exact schema and no other top-level keys: "
                + "{\"folders\":[{\"label\":\"short folder label\",\"ids\":[0,1]}]}. "
                + "If no folders should be created, return {\"folders\":[]}. "
                + "Use only ids from the provided app list. Never duplicate an id. "
                + "Create folders only when at least 2 apps clearly belong together. "
                + "Leave uncertain apps and singleton categories out. "
                + "Keep labels short and suitable for a phone launcher. "
                + "Existing user folders are already omitted from the app list and must remain unchanged. "
                + "Example valid output: {\"folders\":[{\"label\":\"Messaging\",\"ids\":[0,4,9]}]}.";
    }

    private JSONObject buildRepairRequest(
            Config config,
            String invalidContent,
            Throwable parseError,
            boolean requestJsonMode
    ) throws JSONException {
        String repairPrompt = "The previous model response was not usable launcher-folder JSON. "
                + "Convert it into one valid JSON object with exactly this schema:\n"
                + "{\"folders\":[{\"label\":\"short folder label\",\"ids\":[0,1]}]}\n"
                + "Rules:\n"
                + "- Return only JSON. No Markdown, no code fences, no explanation.\n"
                + "- Keep only folders that have at least two integer ids.\n"
                + "- If nothing valid can be recovered, return {\"folders\":[]}.\n\n"
                + "Parse error:\n"
                + (parseError == null ? "unknown" : String.valueOf(parseError.getMessage()))
                + "\n\nPrevious response:\n"
                + trimRepairContent(invalidContent);

        JSONArray messages = new JSONArray()
                .put(message("system", "You repair malformed model output into strict JSON. "
                        + "Your entire reply must be one JSON object and nothing else."))
                .put(message("user", repairPrompt));

        JSONObject request = new JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 1200);
        if (requestJsonMode) {
            request.put("response_format", new JSONObject().put("type", "json_object"));
        }
        return request;
    }

    private String requestWithJsonModeFallback(
            Context context,
            String apiKey,
            JSONObject jsonModeRequest,
            JSONObject plainRequest
    ) throws Exception {
        try {
            return requestCompletion(context, apiKey, jsonModeRequest.toString());
        } catch (IOException e) {
            if (!isResponseFormatFailure(e)) throw e;
            return requestCompletion(context, apiKey, plainRequest.toString());
        }
    }

    private String requestCompletion(Context context, String apiKey, String requestBody)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean bound = new AtomicBoolean(false);
        AtomicReference<String> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Handler replyHandler = new Handler(Looper.getMainLooper(), message -> {
            Bundle data = message.getData();
            if (message.what == OpenRouterProxyService.MSG_SUCCESS) {
                responseRef.set(data == null ? "" :
                        data.getString(OpenRouterProxyService.EXTRA_RESPONSE_BODY, ""));
            } else if (message.what == OpenRouterProxyService.MSG_ERROR) {
                String error = data == null ? "" :
                        data.getString(OpenRouterProxyService.EXTRA_ERROR, "");
                errorRef.set(new IOException(TextUtils.isEmpty(error)
                        ? "OpenRouter request failed."
                        : error));
            }
            latch.countDown();
            return true;
        });

        Messenger replyMessenger = new Messenger(replyHandler);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    Message request = Message.obtain(null, OpenRouterProxyService.MSG_CHAT_COMPLETION);
                    Bundle data = new Bundle();
                    data.putString(OpenRouterProxyService.EXTRA_API_KEY, apiKey);
                    data.putString(OpenRouterProxyService.EXTRA_REQUEST_BODY, requestBody);
                    request.setData(data);
                    request.replyTo = replyMessenger;
                    new Messenger(service).send(request);
                } catch (RemoteException e) {
                    errorRef.set(e);
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (responseRef.get() == null && errorRef.get() == null) {
                    errorRef.set(new IOException("OpenRouter proxy service disconnected."));
                    latch.countDown();
                }
            }
        };

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE, PROXY_SERVICE_CLASS));
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Could not bind OpenRouter proxy service.");
        }
        bound.set(true);

        try {
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("OpenRouter request timed out.");
            }
            Throwable error = errorRef.get();
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error serious) throw serious;
            if (error != null) throw new IOException(error);
            String response = responseRef.get();
            if (TextUtils.isEmpty(response)) {
                throw new IOException("OpenRouter returned an empty response.");
            }
            return response;
        } finally {
            if (bound.get()) {
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String completionContent(String responseBody) throws JSONException, IOException {
        JSONObject response = new JSONObject(responseBody);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("OpenRouter response has no choices.");
        }

        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) {
            throw new IOException("OpenRouter response has no message.");
        }

        Object content = message.opt("content");
        if (content == null) throw new IOException("OpenRouter response content is empty.");
        String text = content instanceof String ? (String) content : String.valueOf(content);
        if (TextUtils.isEmpty(text)) throw new IOException("OpenRouter response content is empty.");
        return text;
    }

    private static List<Group> parseGroups(String content, List<AppCandidate> apps)
            throws JSONException, IOException {
        JSONObject grouped = parseJsonObject(content);
        JSONArray folders = optArray(grouped, "folders", "groups");
        if (folders == null) return Collections.emptyList();

        Map<Integer, AppCandidate> appById = new HashMap<>();
        for (int i = 0; i < apps.size(); i++) {
            appById.put(i, apps.get(i));
        }

        ArrayList<Group> result = new ArrayList<>();
        Set<String> claimedKeys = new HashSet<>();
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;

            String label = cleanLabel(optString(folder,
                    "label", "name", "folder", "folderName", "title"));
            JSONArray ids = optArray(folder, "ids", "appIds", "app_ids", "apps", "items");
            if (ids == null) continue;

            ArrayList<AppCandidate> groupedApps = new ArrayList<>();
            for (int j = 0; j < ids.length(); j++) {
                AppCandidate app = appById.get(idAt(ids, j));
                if (app == null || claimedKeys.contains(app.key)) continue;
                groupedApps.add(app);
                claimedKeys.add(app.key);
            }

            if (groupedApps.size() >= 2) {
                result.add(new Group(label, groupedApps));
            } else {
                for (AppCandidate app : groupedApps) {
                    claimedKeys.remove(app.key);
                }
            }
        }

        result.sort(Comparator.comparingInt(Group::firstIndex));
        return result;
    }

    private static JSONObject parseJsonObject(String content) throws JSONException, IOException {
        String trimmed = stripCodeFence(content == null ? "" : content.trim());
        try {
            return new JSONObject(trimmed);
        } catch (JSONException ignored) {
            String extracted = extractFirstBalancedJsonObject(trimmed);
            if (!TextUtils.isEmpty(extracted)) {
                return new JSONObject(extracted);
            }
            throw new IOException("OpenRouter did not return folder JSON.");
        }
    }

    private static JSONArray optArray(JSONObject object, String... names) {
        for (String name : names) {
            JSONArray array = object.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
    }

    private static String optString(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "AI";
    }

    private static int idAt(JSONArray array, int index) {
        Object value = array.opt(index);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        if (value instanceof JSONObject object) {
            String id = optString(object, "id", "appId", "app_id");
            try {
                return Integer.parseInt(id.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String extractFirstBalancedJsonObject(String value) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    return value.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private static String stripCodeFence(String value) {
        if (!value.startsWith("```")) return value;
        int firstNewline = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return value.substring(firstNewline + 1, lastFence).trim();
        }
        return value;
    }

    private static String trimPrompt(String prompt) {
        if (prompt == null) return "";
        String trimmed = prompt.trim();
        return trimmed.length() <= MAX_PROMPT_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_PROMPT_CHARS);
    }

    private static String trimRepairContent(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        return trimmed.length() <= 8000 ? trimmed : trimmed.substring(0, 8000);
    }

    private static String cleanLabel(String label) {
        String cleaned = label == null ? "" : label
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        if (TextUtils.isEmpty(cleaned)) return "AI";
        return cleaned.length() <= MAX_LABEL_CHARS
                ? cleaned
                : cleaned.substring(0, MAX_LABEL_CHARS).trim();
    }

    public static final class Config {
        public final String apiKey;
        public final String model;
        public final String customPrompt;

        public Config(String apiKey, String model, String customPrompt) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.model = model == null ? "" : model.trim();
            this.customPrompt = customPrompt == null ? "" : customPrompt.trim();
        }
    }
}
