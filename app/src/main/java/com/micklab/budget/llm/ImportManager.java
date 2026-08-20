package com.micklab.budget.llm;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.data.Record;
import com.micklab.budget.ocr.OcrModelManager;
import com.micklab.budget.ocr.TesseractOcr;
import com.micklab.budget.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一括登録パイプライン: 画像 → OCR（Tesseract） → LLM（GBNF 拘束） → レコード配列。
 * <p>戻り値はまだ DB へ入れていない候補。呼び出し側でプレビュー確認後に登録する。
 * すべて同期実行なのでバックグラウンドスレッドで呼ぶこと。
 */
public class ImportManager {

    private static final String TAG = "ImportManager";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Prefs prefs;
    private final OcrModelManager modelManager;
    private final TesseractOcr ocr;
    private final BudgetRepository repo;

    public ImportManager(Context context) {
        this.prefs = new Prefs(context);
        this.modelManager = new OcrModelManager(context);
        this.ocr = new TesseractOcr(modelManager);
        this.repo = new BudgetRepository(context);
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

    /** 画像1枚から候補レコードを抽出する。 */
    public Result importFromBitmap(Bitmap bitmap) throws Exception {
        List<String> langs = OcrModelManager.parseLangs(prefs.ocrLangs());
        String text = ocr.recognize(bitmap, langs);
        if (text.isEmpty()) {
            throw new TesseractOcr.OcrException("画面から文字を認識できませんでした");
        }

        List<String> categories = repo.getCategoryNames();
        String grammar = BudgetGrammar.build(categories);
        String prompt = buildPrompt(text, categories);

        Log.i(TAG, "LLM へ送信（OCR " + text.length() + " chars, cat " + categories.size() + "）");
        LlmClient llm = new LlmClient(prefs);
        String response = llm.chat(prompt, grammar);
        Log.i(TAG, "LLM 応答: " + response);

        List<Record> records = parseRecords(response);
        return new Result(text, records);
    }

    // ---- prompt -----------------------------------------------------------

    private String buildPrompt(String ocrText, List<String> categories) {
        Calendar cal = Calendar.getInstance();
        String today = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        int year = cal.get(Calendar.YEAR);
        String catList = categories.isEmpty() ? "（定義なし）" : android.text.TextUtils.join("、", categories);

        return "あなたは家計簿入力アシスタントです。以下は画面の OCR 結果で、銀行口座やクレジットカードの"
                + "利用明細が含まれます。各取引を1レコードとして抽出し、指定スキーマの JSON 配列だけを出力してください"
                + "（説明・コードブロックは不要）。\n\n"
                + "各レコードのフィールド:\n"
                + "- date: 取引日。\"YYYY-MM-DD\" 形式。年が無ければ " + year + " 年を補完。日付が全く不明なら "
                + today + "。\n"
                + "- category: 次のいずれか、または空文字。" + catList + "\n"
                + "- item: 費目・摘要（店名や内容）。短く。\n"
                + "- amount: 金額（整数）。支出・引き落とし・カード利用は負の数、入金・収入・返金は正の数。"
                + "カンマや通貨記号（¥ や円）は除く。\n\n"
                + "残高・ヘッダ・合計など取引でない行は無視。取引が無ければ [] を出力。\n\n"
                + "OCR テキスト:\n---\n" + ocrText + "\n---";
    }

    // ---- parsing ----------------------------------------------------------

    /** LLM 応答 JSON からレコード候補を組み立てる。文法拘束済みでもコードフェンス等に耐える。 */
    public static List<Record> parseRecords(String response) throws JSONException {
        List<Record> out = new ArrayList<>();
        String json = extractArray(response);
        if (json == null) return out;

        JSONArray arr = new JSONArray(json);
        String today = todayString();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Record r = new Record();
            r.date = normalizeDate(o.optString("date", ""), today);
            r.category = o.optString("category", "");
            r.item = o.optString("item", "");
            r.amount = o.optLong("amount", 0);
            out.add(r);
        }
        return out;
    }

    /** 応答から最初の '[' … 対応する最後の ']' を切り出す。 */
    private static String extractArray(String s) {
        if (s == null) return null;
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String normalizeDate(String d, String fallback) {
        if (d != null && DATE.matcher(d).matches()) return d;
        return fallback;
    }

    private static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
