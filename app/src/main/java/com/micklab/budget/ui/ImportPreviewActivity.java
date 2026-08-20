package com.micklab.budget.ui;

import android.app.DatePickerDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.micklab.budget.R;
import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.data.Record;
import com.micklab.budget.llm.ImportManager;
import com.micklab.budget.util.AppExecutors;
import com.micklab.budget.util.DateUtil;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;

/**
 * 一括登録プレビュー。画像（ピッカーの content URI か、キャプチャの保存ファイル）を
 * OCR → LLM(GBNF) にかけ、候補レコードを編集可能な表で確認してから登録する。
 */
public class ImportPreviewActivity extends AppCompatActivity implements RecordAdapter.Callbacks {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_IMAGE_PATH = "image_path";
    /** 連続取得でためた OCR テキストを直接渡すモード（画像を再 OCR しない）。 */
    public static final String EXTRA_OCR_TEXT = "ocr_text";

    private BudgetRepository repo;
    private RecordAdapter adapter;

    private ProgressBar progress;
    private TextView status;
    private TextView ocrText;
    private Button registerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_preview);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repo = new BudgetRepository(this);
        adapter = new RecordAdapter(this);

        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        ocrText = findViewById(R.id.ocr_text);
        registerButton = findViewById(R.id.btn_register);
        registerButton.setEnabled(false);
        registerButton.setOnClickListener(v -> registerAll());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());

        runImport();
    }

    private void runImport() {
        progress.setVisibility(View.VISIBLE);
        String ocrTextExtra = getIntent().getStringExtra(EXTRA_OCR_TEXT);
        boolean textMode = ocrTextExtra != null;
        status.setText(textMode ? "OCR 結果を解析しています…" : "画像を OCR し、解析しています…");
        AppExecutors.io(() -> {
            try {
                ImportManager manager = new ImportManager(this);
                ImportManager.Result result;
                if (textMode) {
                    result = new ImportManager.Result(ocrTextExtra, manager.parse(ocrTextExtra));
                } else {
                    Bitmap bitmap = loadBitmap();
                    if (bitmap == null) {
                        throw new IllegalStateException("画像を読み込めませんでした");
                    }
                    result = manager.importFromBitmap(bitmap);
                }
                List<String> cats = repo.getCategoryNames();

                // stable id 衝突を避けるため一時的な負の id を割り当てる
                for (int i = 0; i < result.records.size(); i++) {
                    result.records.get(i).id = -(i + 1);
                }
                AppExecutors.main(() -> onImported(result, cats));
            } catch (Exception e) {
                AppExecutors.main(() -> onError(e));
            }
        });
    }

    private void onImported(ImportManager.Result result, List<String> cats) {
        progress.setVisibility(View.GONE);
        ocrText.setText(result.ocrText);
        adapter.setCategories(cats);
        adapter.setItems(result.records);
        if (result.records.isEmpty()) {
            status.setText("取引を抽出できませんでした。OCR テキストを確認してください。");
            registerButton.setEnabled(false);
        } else {
            status.setText(result.records.size() + " 件の候補を抽出しました。確認・編集して登録してください。");
            registerButton.setEnabled(true);
        }
    }

    private void onError(Exception e) {
        progress.setVisibility(View.GONE);
        status.setText("エラー: " + e.getMessage());
        Toast.makeText(this, "解析に失敗しました", Toast.LENGTH_LONG).show();
    }

    private void registerAll() {
        List<Record> candidates = adapter.items();
        if (candidates.isEmpty()) return;
        registerButton.setEnabled(false);
        // ループ中に adapter の内部リストが変化しないようコピーを作る
        final List<Record> toInsert = new java.util.ArrayList<>(candidates);
        AppExecutors.io(() -> {
            for (Record r : toInsert) {
                r.id = 0; // 新規採番させる
                repo.insertRecord(r);
            }
            AppExecutors.main(() -> {
                Toast.makeText(this, toInsert.size() + " 件を登録しました", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    // ---- RecordAdapter.Callbacks（プレビューは在メモリ編集）---------------

    @Override
    public void onRecordChanged(Record r) {
        // 候補はまだ未保存。Record は在メモリで直接更新済みなので何もしない。
    }

    @Override
    public void onRecordDeleted(Record r, int position) {
        adapter.removeAt(position);
    }

    @Override
    public void onPickDate(Record r, int position) {
        Calendar c = DateUtil.parse(r.date);
        new DatePickerDialog(this, (view, year, month, day) -> {
            r.date = DateUtil.iso(year, month, day);
            adapter.notifyItemChanged(position);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---- bitmap -----------------------------------------------------------

    private Bitmap loadBitmap() throws Exception {
        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (path != null) {
            BitmapFactory.decodeFile(path, bounds);
        } else if (uriStr != null) {
            try (InputStream is = openUri(uriStr)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
        } else {
            return null;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2200);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (path != null) {
            return BitmapFactory.decodeFile(path, opts);
        }
        try (InputStream is = openUri(uriStr)) {
            return BitmapFactory.decodeStream(is, null, opts);
        }
    }

    private InputStream openUri(String uriStr) throws Exception {
        return getContentResolver().openInputStream(Uri.parse(uriStr));
    }

    private static int sampleSize(int w, int h, int maxDim) {
        int longest = Math.max(w, h);
        int sample = 1;
        while (longest / sample > maxDim) {
            sample *= 2;
        }
        return sample;
    }
}
