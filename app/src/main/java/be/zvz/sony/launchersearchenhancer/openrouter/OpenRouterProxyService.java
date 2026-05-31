package be.zvz.sony.launchersearchenhancer.openrouter;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;

public final class OpenRouterProxyService extends Service {

    public static final int MSG_CHAT_COMPLETION = 1;
    public static final int MSG_SUCCESS = 2;
    public static final int MSG_ERROR = 3;

    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_REQUEST_BODY = "request_body";
    public static final String EXTRA_RESPONSE_BODY = "response_body";
    public static final String EXTRA_ERROR = "error";

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String TARGET_LAUNCHER_PACKAGE = "com.sonymobile.launcher";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 75_000;
    private static final int ERROR_BODY_LIMIT_BYTES = 4096;

    private final ExecutorService worker = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "XLS-OpenRouter");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Messenger messenger = new Messenger(new IncomingHandler(this));

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void handleChatCompletion(Message message) {
        Messenger replyTo = message.replyTo;
        if (replyTo == null) return;

        if (!isAllowedCaller(message.sendingUid)) {
            sendError(replyTo, "Caller is not allowed.");
            return;
        }

        Bundle data = message.getData();
        String apiKey = data == null ? "" : data.getString(EXTRA_API_KEY, "");
        String requestBody = data == null ? "" : data.getString(EXTRA_REQUEST_BODY, "");
        if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(requestBody)) {
            sendError(replyTo, "OpenRouter API key or request body is empty.");
            return;
        }

        worker.execute(() -> {
            try {
                sendSuccess(replyTo, postChatCompletion(apiKey, requestBody));
            } catch (Throwable t) {
                sendError(replyTo, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        });
    }

    private boolean isAllowedCaller(int uid) {
        if (uid == Process.myUid()) return true;
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (TARGET_LAUNCHER_PACKAGE.equals(packageName)) return true;
            if (getPackageName().equals(packageName)) return true;
        }
        return false;
    }

    private static String postChatCompletion(String apiKey, String requestBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(OPENROUTER_URL).openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IOException("OpenRouter endpoint must use HTTPS.");
        }

        byte[] bytes = requestBody.getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("HTTP-Referer",
                "https://github.com/organization/Xperia-Launcher-Search-Enhancer");
        connection.setRequestProperty("X-OpenRouter-Title", "Xperia Launcher Search Enhancer");

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }

        int responseCode = connection.getResponseCode();
        InputStream responseStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseBody = readUtf8(responseStream, responseCode >= 200 && responseCode < 300
                ? Integer.MAX_VALUE
                : ERROR_BODY_LIMIT_BYTES);
        connection.disconnect();

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("OpenRouter HTTP " + responseCode + ": " + responseBody);
        }
        return responseBody;
    }

    private static String readUtf8(InputStream inputStream, int maxBytes) throws IOException {
        if (inputStream == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            int remaining = maxBytes - total;
            if (remaining <= 0) break;
            int count = Math.min(read, remaining);
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void sendSuccess(Messenger replyTo, String responseBody) {
        Message response = Message.obtain(null, MSG_SUCCESS);
        Bundle data = new Bundle();
        data.putString(EXTRA_RESPONSE_BODY, responseBody);
        response.setData(data);
        send(replyTo, response);
    }

    private static void sendError(Messenger replyTo, String error) {
        Message response = Message.obtain(null, MSG_ERROR);
        Bundle data = new Bundle();
        data.putString(EXTRA_ERROR, error);
        response.setData(data);
        send(replyTo, response);
    }

    private static void send(Messenger replyTo, Message response) {
        try {
            replyTo.send(response);
        } catch (RemoteException ignored) {
        }
    }

    private static final class IncomingHandler extends Handler {
        private final WeakReference<OpenRouterProxyService> serviceRef;

        IncomingHandler(OpenRouterProxyService service) {
            super(Looper.getMainLooper());
            serviceRef = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message message) {
            OpenRouterProxyService service = serviceRef.get();
            if (service == null) return;

            if (message.what == MSG_CHAT_COMPLETION) {
                service.handleChatCompletion(message);
            } else {
                super.handleMessage(message);
            }
        }
    }
}
