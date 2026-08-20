package com.micklab.budget.llm;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.micklab.budget.data.Record;
import com.micklab.budget.ocr.OcrModelManager;
import com.micklab.budget.ocr.OcrRecordParser;
import com.micklab.budget.ocr.TesseractOcr;
import com.micklab.budget.util.Prefs;

import java.util.List;

/**
 * 一括登録パイプライン: 画像 → OCR（Tesseract） → 後続処理（{@link OcrRecordParser}）→ レコード候補。
 * <p>LLM の利用は一旦保留し、OCR 結果を要素ごとに整えてレコード化する。戻り値はまだ DB へ
 * 入れていない候補。呼び出し側でプレビュー確認後に登録する。バックグラウンドスレッドで呼ぶこと。
 *
 * <p>LLM 経路（{@link LlmClient} + {@link BudgetGrammar}）は将来の再有効化に備えて残している。
 */
public class ImportManager {

    private static final String TAG = "ImportManager";

    private final Prefs prefs;
    private final OcrModelManager modelManager;
    private final TesseractOcr ocr;

    public ImportManager(Context context) {
        this.prefs = new Prefs(context);
        this.modelManager = new OcrModelManager(context);
        this.ocr = new TesseractOcr(modelManager);
    }

    public OcrModelManager modelManager() {
        return modelManager;
    }

    /** 抽出結果（OCR テキストと候補レコード）。 */
    public static class Result {
        public final String ocrText;
        public final List<Record> records;

        public Result(String ocrText, List<Record> records) {
            this.ocrText = ocrText;
            this.records = records;
        }
    }

    /** 画像1枚を OCR してテキストを返す（連続取得で使用）。 */
    public String recognize(Bitmap bitmap) throws Exception {
        List<String> langs = OcrModelManager.parseLangs(prefs.ocrLangs());
        return ocr.recognize(bitmap, langs, prefs.ocrDpi());
    }

    /** OCR テキストを後続処理でレコード候補に整える。 */
    public List<Record> parse(String ocrText) {
        return OcrRecordParser.parse(ocrText);
    }

    /** 画像1枚から候補レコードを抽出する（OCR → 後続処理）。 */
    public Result importFromBitmap(Bitmap bitmap) throws Exception {
        String text = recognize(bitmap);
        if (text.isEmpty()) {
            throw new TesseractOcr.OcrException("画面から文字を認識できませんでした");
        }
        List<Record> records = OcrRecordParser.parse(text);
        Log.i(TAG, "OCR " + text.length() + " chars → " + records.size() + " 件");
        return new Result(text, records);
    }
}
