package com.micklab.budget.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.micklab.budget.R;
import com.micklab.budget.capture.CaptureActivity;
import com.micklab.budget.capture.ScreenCaptureService;
import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.data.Record;
import com.micklab.budget.ocr.OcrInbox;
import com.micklab.budget.overlay.FloatingButtonService;
import com.micklab.budget.util.AppExecutors;
import com.micklab.budget.util.DateUtil;

import java.util.Calendar;

/** 家計簿のメイン画面。レコード表を直接編集し、各機能へ遷移する。 */
public class MainActivity extends AppCompatActivity implements RecordAdapter.Callbacks {

    private BudgetRepository repo;
    private RecordAdapter adapter;
    private RecyclerView recycler;

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) openImportPreview(uri);
            });

    private final ActivityResultLauncher<String> requestNotif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));

        repo = new BudgetRepository(this);
        adapter = new RecordAdapter(this);

        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> addRecord());

        // フロートボタン ON/OFF を直接起動
        FloatingActionButton fabFloat = findViewById(R.id.fab_float);
        fabFloat.setOnClickListener(v -> toggleFloatingButton());

        // 取得対象アプリを個別指定して取り込む（単一アプリ／全画面を選択）を直接起動
        FloatingActionButton fabCapture = findViewById(R.id.fab_capture);
        fabCapture.setOnClickListener(v -> startAppCapture());
    }

    /** 取得対象アプリを個別指定して1枚取得する（Android 14+ は単一アプリ選択が可能）。 */
    private void startAppCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        Intent i = new Intent(this, CaptureActivity.class);
        i.putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_ONESHOT);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleFloatHandoff();
        reload();
    }

    /** フロート連続取得から戻った場合: ボタンを無効化し、ためた OCR を後続処理へ渡す。 */
    private void handleFloatHandoff() {
        if (FloatingButtonService.isRunning() && OcrInbox.size() > 0) {
            String text = OcrInbox.drainJoined();
            stopService(new Intent(this, FloatingButtonService.class));
            stopService(new Intent(this, ScreenCaptureService.class));
            Intent i = new Intent(this, ImportPreviewActivity.class);
            i.putExtra(ImportPreviewActivity.EXTRA_OCR_TEXT, text);
            startActivity(i);
        }
    }

    private void reload() {
        AppExecutors.io(() -> {
            java.util.List<Record> records = repo.getAllRecords();
            java.util.List<String> cats = repo.getCategoryNames();
            AppExecutors.main(() -> {
                adapter.setCategories(cats);
                adapter.setItems(records);
            });
        });
    }

    private void addRecord() {
        Record r = new Record();
        r.date = DateUtil.today();
        r.category = "";
        r.item = "";
        r.amount = 0;
        AppExecutors.io(() -> {
            repo.insertRecord(r); // r.id 採番
            AppExecutors.main(() -> {
                adapter.addAtTop(r);
                recycler.scrollToPosition(0);
            });
        });
    }

    // ---- RecordAdapter.Callbacks -----------------------------------------

    @Override
    public void onRecordChanged(Record r) {
        AppExecutors.io(() -> repo.updateRecord(r));
    }

    @Override
    public void onRecordDeleted(Record r, int position) {
        adapter.removeAt(position);
        AppExecutors.io(() -> repo.deleteRecord(r.id));
        Snackbar.make(recycler, "1件削除しました", Snackbar.LENGTH_LONG)
                .setAction("元に戻す", v -> AppExecutors.io(() -> {
                    r.id = 0;
                    repo.insertRecord(r);
                    AppExecutors.main(this::reload);
                }))
                .show();
    }

    @Override
    public void onPickDate(Record r, int position) {
        Calendar c = DateUtil.parse(r.date);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            r.date = DateUtil.iso(year, month, day);
            AppExecutors.io(() -> repo.updateRecord(r));
            adapter.notifyItemChanged(position);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    // ---- menu -------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_import) {
            pickImage.launch("image/*");
            return true;
        } else if (id == R.id.menu_report) {
            startActivity(new Intent(this, ReportActivity.class));
            return true;
        } else if (id == R.id.menu_categories) {
            startActivity(new Intent(this, CategoriesActivity.class));
            return true;
        } else if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openImportPreview(Uri uri) {
        Intent i = new Intent(this, ImportPreviewActivity.class);
        i.putExtra(ImportPreviewActivity.EXTRA_IMAGE_URI, uri.toString());
        startActivity(i);
    }

    private void toggleFloatingButton() {
        if (FloatingButtonService.isRunning()) {
            stopService(new Intent(this, FloatingButtonService.class));
            stopService(new Intent(this, ScreenCaptureService.class)); // 取得セッションも終了
            Toast.makeText(this, "フロートボタンを停止しました", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "他アプリ上に表示する権限を許可してください", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        androidx.core.content.ContextCompat.startForegroundService(
                this, new Intent(this, FloatingButtonService.class));
        // 有効化時に「全画面」の同意を取り、キャプチャセッションを先に確立しておく。
        // 以降はフロートボタンのタップだけで、アプリ切替や選択ダイアログなしに全画面を取得する。
        Intent capture = new Intent(this, CaptureActivity.class);
        capture.putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_CONTINUOUS);
        startActivity(capture);
        Toast.makeText(this, "フロートボタン表示中。画面の取得を許可してください", Toast.LENGTH_LONG).show();
    }
}
