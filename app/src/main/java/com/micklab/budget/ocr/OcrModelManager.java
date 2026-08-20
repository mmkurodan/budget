package com.micklab.budget.ocr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Tesseract の学習データ（*.traineddata）を端末内 filesDir/tessdata に用意する。
 * <p>アプリ内ダウンロード（tessdata_fast）と assets 同梱の両対応。モデルが無い場合でも
 * アプリはビルド・起動でき、OCR 実行時に明示エラーになる（無言で空文字にはしない）。
 */
public class OcrModelManager {

    private static final String TAG = "OcrModelManager";
    private static final String TESSDATA = "tessdata";
    private static final String SUFFIX = ".traineddata";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/";

    private final Context context;

    public OcrModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** TessBaseAPI.init に渡す base path（この直下に tessdata/ がある）。 */
    public String tessBasePath() {
        return context.getFilesDir().getAbsolutePath();
    }

    public File tessDataDir() {
        File dir = new File(context.getFilesDir(), TESSDATA);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public File modelFile(String lang) {
        return new File(tessDataDir(), lang + SUFFIX);
    }

    public boolean hasLanguage(String lang) {
        File f = modelFile(lang);
        return f.exists() && f.length() > 0;
    }

    public boolean hasAllLanguages(List<String> langs) {
        for (String l : langs) {
            if (!hasLanguage(l)) return false;
        }
        return true;
    }

    /** "jpn+eng" のような指定を分解する。 */
    public static List<String> parseLangs(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) return out;
        for (String part : spec.split("\\+")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** assets/tessdata に同梱された学習データがあれば、未配置分を filesDir へ展開する。 */
    public void ensureAssetsCopied() {
        try {
            String[] assets = context.getAssets().list(TESSDATA);
            if (assets == null) return;
            for (String name : assets) {
                if (!name.endsWith(SUFFIX)) continue;
                File dst = new File(tessDataDir(), name);
                if (dst.exists() && dst.length() > 0) continue;
                try (InputStream in = context.getAssets().open(TESSDATA + "/" + name)) {
                    writeAtomically(in, dst);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "assets tessdata コピー失敗", e);
        }
    }

    /**
     * 指定言語をダウンロードして配置する。既にあれば何もしない。
     *
     * @return 取得に失敗した（未配置のままの）言語。空なら全て揃っている。
     */
    public List<String> ensureLanguages(List<String> langs) {
        ensureAssetsCopied();
        List<String> missing = new ArrayList<>();
        for (String lang : langs) {
            if (hasLanguage(lang)) continue;
            if (!download(lang)) {
                missing.add(lang);
            }
        }
        return missing;
    }

    private boolean download(String lang) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + lang + SUFFIX);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "ダウンロード失敗 " + lang + " HTTP " + code);
                return false;
            }
            File dst = modelFile(lang);
            try (InputStream in = conn.getInputStream()) {
                writeAtomically(in, dst);
            }
            Log.i(TAG, "ダウンロード完了 " + lang + " (" + dst.length() + " bytes)");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "ダウンロード例外 " + lang, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** temp へ書いてから rename して、破損した部分ファイルを残さない。 */
    private void writeAtomically(InputStream in, File dst) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }
        if (dst.exists() && !dst.delete()) {
            throw new IOException("旧モデルを削除できません: " + dst);
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("モデルの確定に失敗: " + dst);
        }
    }
}
