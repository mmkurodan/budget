package com.micklab.budget.ocr;

import android.graphics.Bitmap;
import android.util.Log;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.util.List;

/**
 * Tesseract（tesseract4android）によるオフライン OCR。多言語対応（例 jpn+eng）。
 * スクリーンショットの銀行口座 / クレジットカード履歴などのテキストを読み取る。
 */
public class TesseractOcr {

    private static final String TAG = "TesseractOcr";

    private final OcrModelManager models;

    public TesseractOcr(OcrModelManager models) {
        this.models = models;
    }

    public static class OcrException extends Exception {
        public OcrException(String message) {
            super(message);
        }
    }

    /** 画像からテキストを認識する。学習データが無い場合は例外。dpi は user_defined_dpi。 */
    public String recognize(Bitmap bitmap, List<String> langs, int dpi) throws OcrException {
        models.ensureAssetsCopied();
        if (langs.isEmpty()) {
            throw new OcrException("OCR 言語が未設定です");
        }
        if (!models.hasAllLanguages(langs)) {
            throw new OcrException("OCR 学習データが未配置です（設定からダウンロードしてください）: " + langs);
        }

        TessBaseAPI api = new TessBaseAPI();
        try {
            String langArg = android.text.TextUtils.join("+", langs);
            if (!api.init(models.tessBasePath(), langArg)) {
                throw new OcrException("Tesseract の初期化に失敗しました: " + langArg);
            }
            // 読み込み DPI を明示（未指定だと 70dpi 想定になり小さい文字の認識が落ちる）。
            api.setVariable("user_defined_dpi", String.valueOf(dpi > 0 ? dpi : 400));
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setImage(toArgb8888(bitmap));
            String text = api.getUTF8Text();
            if (text == null) text = "";
            Log.i(TAG, "OCR 完了: " + text.length() + " chars");
            return text.trim();
        } finally {
            try {
                api.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    /** Tesseract は ARGB_8888 を要求する。 */
    private static Bitmap toArgb8888(Bitmap src) {
        if (src.getConfig() == Bitmap.Config.ARGB_8888) return src;
        return src.copy(Bitmap.Config.ARGB_8888, false);
    }
}
