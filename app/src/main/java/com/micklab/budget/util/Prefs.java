package com.micklab.budget.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * LLM / OCR 接続設定などの永続化。既定値はローカル llama サーバ
 * （/root/llama アプリ, 既定ポート 11434）に向く。
 */
public final class Prefs {

    private static final String FILE = "budget_prefs";

    private static final String KEY_BASE_URL = "llm_base_url";
    private static final String KEY_API_TYPE = "llm_api_type"; // "ollama" | "openai"
    private static final String KEY_MODEL = "llm_model";
    private static final String KEY_API_KEY = "llm_api_key";
    private static final String KEY_OCR_LANGS = "ocr_langs";
    private static final String KEY_OCR_DPI = "ocr_dpi";
    private static final String KEY_EXCLUDE_SAFE_AREA = "exclude_safe_area";

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    public static final String DEFAULT_API_TYPE = "ollama";
    public static final String DEFAULT_MODEL = "default";
    public static final String DEFAULT_OCR_LANGS = "jpn+eng";
    public static final int DEFAULT_OCR_DPI = 400;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        return sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public void setBaseUrl(String v) {
        sp.edit().putString(KEY_BASE_URL, v).apply();
    }

    public String apiType() {
        return sp.getString(KEY_API_TYPE, DEFAULT_API_TYPE);
    }

    public void setApiType(String v) {
        sp.edit().putString(KEY_API_TYPE, v).apply();
    }

    public boolean isOpenAi() {
        return "openai".equalsIgnoreCase(apiType());
    }

    public String model() {
        return sp.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void setModel(String v) {
        sp.edit().putString(KEY_MODEL, v).apply();
    }

    public String apiKey() {
        return sp.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String v) {
        sp.edit().putString(KEY_API_KEY, v).apply();
    }

    /** Tesseract 言語（例: "jpn+eng"）。 */
    public String ocrLangs() {
        return sp.getString(KEY_OCR_LANGS, DEFAULT_OCR_LANGS);
    }

    public void setOcrLangs(String v) {
        sp.edit().putString(KEY_OCR_LANGS, v).apply();
    }

    /** OCR 読み込み時の DPI（Tesseract の user_defined_dpi）。既定 400。 */
    public int ocrDpi() {
        return sp.getInt(KEY_OCR_DPI, DEFAULT_OCR_DPI);
    }

    public void setOcrDpi(int v) {
        sp.edit().putInt(KEY_OCR_DPI, v > 0 ? v : DEFAULT_OCR_DPI).apply();
    }

    /** 取得時にセーフエリア（ステータスバー・ナビゲーションバー等）を除外するか。既定 true。 */
    public boolean excludeSafeArea() {
        return sp.getBoolean(KEY_EXCLUDE_SAFE_AREA, true);
    }

    public void setExcludeSafeArea(boolean v) {
        sp.edit().putBoolean(KEY_EXCLUDE_SAFE_AREA, v).apply();
    }
}
