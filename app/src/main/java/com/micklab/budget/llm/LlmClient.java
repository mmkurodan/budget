package com.micklab.budget.llm;

import android.util.Log;

import com.micklab.budget.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ローカル llama サーバ（/root/llama, 既定 127.0.0.1:11434）向けの薄い HTTP クライアント。
 * Ollama（/api/chat, /api/tags）と OpenAI 互換（/v1/chat/completions, /v1/models）の両対応。
 * <p>{@code grammar}（GBNF）を同梱して構造化出力を強制する。
 */
public class LlmClient {

    private static final String TAG = "LlmClient";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 300000; // 冷えたモデルの初回生成に備える
    private static final int PING_TIMEOUT_MS = 8000;

    private final Prefs prefs;

    public LlmClient(Prefs prefs) {
        this.prefs = prefs;
    }

    public static class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** モデル一覧エンドポイントに応答すれば true。 */
    public boolean ping() {
        try {
            HttpURLConnection conn = open(modelsUrl(), "GET", PING_TIMEOUT_MS);
            try {
                return conn.getResponseCode() / 100 == 2;
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    public List<String> listModels() throws LlmException {
        List<String> out = new ArrayList<>();
        String body = httpGet(modelsUrl());
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = prefs.isOpenAi() ? root.optJSONArray("data") : root.optJSONArray("models");
            String field = prefs.isOpenAi() ? "id" : "name";
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        String name = o.optString(field, null);
                        if (name != null && !out.contains(name)) out.add(name);
                    }
                }
            }
        } catch (JSONException e) {
            throw new LlmException("モデル一覧の解析に失敗: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * ユーザープロンプトを送り、{@code grammar}（GBNF, null 可）で拘束した応答テキストを得る。
     */
    public String chat(String prompt, String grammar) throws LlmException {
        try {
            JSONObject body = new JSONObject();
            body.put("model", prefs.model());
            body.put("stream", false);
            if (grammar != null && !grammar.isEmpty()) {
                body.put("grammar", grammar);
            }

            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", prompt);
            messages.put(user);
            body.put("messages", messages);

            String url = prefs.isOpenAi() ? chatOpenAiUrl() : chatOllamaUrl();
            String response = httpPost(url, body.toString());
            return prefs.isOpenAi() ? parseOpenAi(response) : parseOllama(response);
        } catch (JSONException e) {
            throw new LlmException("リクエスト生成に失敗: " + e.getMessage(), e);
        }
    }

    // ---- parsing ----------------------------------------------------------

    private String parseOllama(String raw) throws LlmException {
        try {
            JSONObject o = new JSONObject(raw);
            String err = o.optString("error", "");
            if (!err.isEmpty()) throw new LlmException("サーバエラー: " + err);
            JSONObject message = o.optJSONObject("message");
            if (message == null) throw new LlmException("応答に message がありません");
            return message.optString("content", "").trim();
        } catch (JSONException e) {
            throw new LlmException("応答の解析に失敗: " + e.getMessage(), e);
        }
    }

    private String parseOpenAi(String raw) throws LlmException {
        try {
            JSONObject o = new JSONObject(raw);
            String err = o.optString("error", "");
            if (!err.isEmpty()) throw new LlmException("サーバエラー: " + err);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new LlmException("応答に choices がありません");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new LlmException("応答に message がありません");
            return message.optString("content", "").trim();
        } catch (JSONException e) {
            throw new LlmException("応答の解析に失敗: " + e.getMessage(), e);
        }
    }

    // ---- urls -------------------------------------------------------------

    private String base() {
        String b = prefs.baseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private String modelsUrl() {
        return base() + (prefs.isOpenAi() ? "/v1/models" : "/api/tags");
    }

    private String chatOllamaUrl() {
        return base() + "/api/chat";
    }

    private String chatOpenAiUrl() {
        return base() + "/v1/chat/completions";
    }

    // ---- http -------------------------------------------------------------

    private String httpGet(String url) throws LlmException {
        try {
            HttpURLConnection conn = open(url, "GET", PING_TIMEOUT_MS);
            return readResponse(conn);
        } catch (IOException e) {
            throw new LlmException("接続失敗: " + e.getMessage(), e);
        }
    }

    private String httpPost(String url, String body) throws LlmException {
        try {
            HttpURLConnection conn = open(url, "POST", READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return readResponse(conn);
        } catch (IOException e) {
            throw new LlmException("接続失敗: " + e.getMessage(), e);
        }
    }

    private HttpURLConnection open(String url, String method, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("Accept", "application/json");
        String key = prefs.apiKey();
        if (key != null && !key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws LlmException {
        try {
            int code = conn.getResponseCode();
            InputStream is = (code / 100 == 2) ? conn.getInputStream() : conn.getErrorStream();
            String text = is == null ? "" : readAll(is);
            if (code / 100 != 2) {
                Log.w(TAG, "HTTP " + code + ": " + text);
                throw new LlmException("HTTP " + code + ": " + trim(text, 300));
            }
            return text;
        } catch (IOException e) {
            throw new LlmException("応答の読み取りに失敗: " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
