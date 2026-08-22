package com.micklab.budget.ui;

import android.app.DatePickerDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.micklab.budget.R;
import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.data.Record;
import com.micklab.budget.llm.ImportManager;
import com.micklab.budget.ocr.OcrRecordParser;
import com.micklab.budget.util.AppExecutors;
import com.micklab.budget.util.DateUtil;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一括登録プレビュー。OCR 結果を 日付／費目／金額 の3列マトリクスで表示し、各セルを編集できる。
 * セル単位で「上に挿入」（その列の下が繰り下がる）／「削除」（その列の下が繰り上がる）が可能。
 * 3列の行数が揃っている時だけ登録できる（揃わなければ登録不可）。
 */
public class ImportPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_IMAGE_PATH = "image_path";
    /** 連続取得でためた OCR テキストを直接渡すモード（画像を再 OCR しない）。 */
    public static final String EXTRA_OCR_TEXT = "ocr_text";

    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private BudgetRepository repo;

    // 3列の候補（不揃いのことがある）
    private final List<String> dates = new ArrayList<>();
    private final List<String> items = new ArrayList<>();
    private final List<String> amounts = new ArrayList<>();

    private ProgressBar progress;
    private TextView status;
    private TextView ocrText;
    private LinearLayout colDate;
    private LinearLayout colItem;
    private LinearLayout colAmount;
    private Button registerButton;

    private float density;
    private int selectableBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_preview);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repo = new BudgetRepository(this);
        density = getResources().getDisplayMetrics().density;
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        selectableBg = tv.resourceId;

        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        ocrText = findViewById(R.id.ocr_text);
        colDate = findViewById(R.id.col_date);
        colItem = findViewById(R.id.col_item);
        colAmount = findViewById(R.id.col_amount);

        registerButton = findViewById(R.id.btn_register);
        registerButton.setEnabled(false);
        registerButton.setOnClickListener(v -> registerAll());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());

        runImport();
    }

    // ---- OCR / parse ------------------------------------------------------

    private void runImport() {
        progress.setVisibility(View.VISIBLE);
        String ocrTextExtra = getIntent().getStringExtra(EXTRA_OCR_TEXT);
        boolean textMode = ocrTextExtra != null;
        status.setText(textMode ? "OCR 結果を解析しています…" : "画像を OCR しています…");
        AppExecutors.io(() -> {
            try {
                String text;
                if (textMode) {
                    text = ocrTextExtra;
                } else {
                    Bitmap bitmap = loadBitmap();
                    if (bitmap == null) {
                        throw new IllegalStateException("画像を読み込めませんでした");
                    }
                    text = new ImportManager(this).recognize(bitmap);
                }
                final String ocrResult = text;
                OcrRecordParser.Columns cols = OcrRecordParser.parseColumns(text);
                AppExecutors.main(() -> onParsed(ocrResult, cols));
            } catch (Exception e) {
                AppExecutors.main(() -> onError(e));
            }
        });
    }

    private void onParsed(String text, OcrRecordParser.Columns cols) {
        progress.setVisibility(View.GONE);
        ocrText.setText(TextUtils.join("\n", OcrRecordParser.elements(text)));
        dates.clear();
        dates.addAll(cols.dates);
        items.clear();
        items.addAll(cols.items);
        amounts.clear();
        amounts.addAll(cols.amounts);
        rebuild();
    }

    private void onError(Exception e) {
        progress.setVisibility(View.GONE);
        status.setText("エラー: " + e.getMessage());
        Toast.makeText(this, "解析に失敗しました", Toast.LENGTH_LONG).show();
    }

    // ---- matrix -----------------------------------------------------------

    private void rebuild() {
        populateColumn(colDate, dates, InputType.TYPE_CLASS_TEXT, true, false);
        populateColumn(colItem, items, InputType.TYPE_CLASS_TEXT, false, false);
        populateColumn(colAmount, amounts,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, false, true);
        updateSaveState();
    }

    private void populateColumn(LinearLayout col, List<String> list, int inputType,
                                boolean isDate, boolean isAmount) {
        col.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            addValueCell(col, list, i, inputType, isDate, isAmount);
        }
        addAppendCell(col, list);
    }

    private void addValueCell(LinearLayout col, List<String> list, int index, int inputType,
                              boolean isDate, boolean isAmount) {
        View cell = getLayoutInflater().inflate(R.layout.cell_matrix, col, false);
        EditText edit = cell.findViewById(R.id.cell_edit);
        View menu = cell.findViewById(R.id.cell_menu);
        View sign = cell.findViewById(R.id.cell_sign);
        edit.setInputType(inputType);
        edit.setText(list.get(index));
        if (isAmount) {
            sign.setVisibility(View.VISIBLE);
            sign.setOnClickListener(v -> {   // ワンタップで金額の +/- を反転
                long v0 = RecordAdapter.parseAmount(edit.getText().toString());
                edit.setText(String.valueOf(-v0));
            });
        }
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (index < list.size()) list.set(index, s.toString());
            }
        });
        menu.setOnClickListener(v -> showCellMenu(v, list, index, isDate, edit));
        col.addView(cell);
    }

    private void addAppendCell(LinearLayout col, List<String> list) {
        TextView add = new TextView(this);
        add.setText("＋追加");
        add.setTextSize(12);
        add.setGravity(Gravity.CENTER);
        add.setMinHeight((int) (40 * density));
        add.setClickable(true);
        add.setFocusable(true);
        if (selectableBg != 0) add.setBackgroundResource(selectableBg);
        add.setOnClickListener(v -> {
            list.add("");
            rebuild();
        });
        col.addView(add, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showCellMenu(View anchor, List<String> list, int index, boolean isDate, EditText edit) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "上に1行挿入");
        pm.getMenu().add(0, 2, 1, "削除");
        if (isDate) pm.getMenu().add(0, 3, 2, "日付を選択");
        pm.setOnMenuItemClickListener(mi -> {
            int id = mi.getItemId();
            if (id == 1) {
                if (index <= list.size()) list.add(index, ""); // 上に挿入（下が繰り下がる）
                rebuild();
                return true;
            } else if (id == 2) {
                if (index < list.size()) list.remove(index);   // 削除（下が繰り上がる）
                rebuild();
                return true;
            } else if (id == 3) {
                pickDate(edit);
                return true;
            }
            return false;
        });
        pm.show();
    }

    private void pickDate(EditText edit) {
        Calendar c = DateUtil.parse(edit.getText().toString());
        new DatePickerDialog(this, (view, y, m, d) -> edit.setText(DateUtil.iso(y, m, d)),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateSaveState() {
        boolean aligned = !dates.isEmpty()
                && dates.size() == items.size()
                && items.size() == amounts.size();
        registerButton.setEnabled(aligned);
        if (aligned) {
            status.setText("各列 " + dates.size() + " 行で揃っています。登録できます。");
        } else {
            status.setText("行数が不一致です（日付 " + dates.size()
                    + " / 費目 " + items.size() + " / 金額 " + amounts.size()
                    + "）。⋮の挿入・削除で揃えてください。");
        }
    }

    // ---- register ---------------------------------------------------------

    private void registerAll() {
        if (dates.isEmpty() || dates.size() != items.size() || items.size() != amounts.size()) {
            return; // 揃っていなければ登録不可
        }
        final List<Record> toInsert = new ArrayList<>();
        for (int k = 0; k < dates.size(); k++) {
            Record r = new Record();
            r.date = normalizeDate(dates.get(k));
            r.category = "";
            r.item = items.get(k).trim();
            r.amount = RecordAdapter.parseAmount(amounts.get(k));
            toInsert.add(r);
        }
        registerButton.setEnabled(false);
        AppExecutors.io(() -> {
            for (Record r : toInsert) {
                r.id = 0;
                repo.insertRecord(r);
            }
            AppExecutors.main(() -> {
                Toast.makeText(this, toInsert.size() + " 件を登録しました", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private String normalizeDate(String s) {
        if (s != null && ISO.matcher(s.trim()).matches()) return s.trim();
        return DateUtil.today();
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
