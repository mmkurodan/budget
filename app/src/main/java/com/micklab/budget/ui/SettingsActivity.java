package com.micklab.budget.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.micklab.budget.R;
import com.micklab.budget.llm.LlmClient;
import com.micklab.budget.ocr.OcrModelManager;
import com.micklab.budget.util.AppExecutors;
import com.micklab.budget.util.Prefs;

import java.util.List;

/** LLM 接続先・モデル・OCR 言語などの設定。 */
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private EditText baseUrl;
    private EditText model;
    private EditText apiKey;
    private EditText ocrLangs;
    private RadioGroup apiType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = new Prefs(this);
        baseUrl = findViewById(R.id.set_base_url);
        model = findViewById(R.id.set_model);
        apiKey = findViewById(R.id.set_api_key);
        ocrLangs = findViewById(R.id.set_ocr_langs);
        apiType = findViewById(R.id.set_api_type);

        baseUrl.setText(prefs.baseUrl());
        model.setText(prefs.model());
        apiKey.setText(prefs.apiKey());
        ocrLangs.setText(prefs.ocrLangs());
        apiType.check(prefs.isOpenAi() ? R.id.type_openai : R.id.type_ollama);

        ((Button) findViewById(R.id.btn_ping)).setOnClickListener(v -> ping());
        ((Button) findViewById(R.id.btn_models)).setOnClickListener(v -> listModels());
        ((Button) findViewById(R.id.btn_ocr_dl)).setOnClickListener(v -> downloadOcr());
        ((Button) findViewById(R.id.btn_overlay_perm)).setOnClickListener(v -> requestOverlay());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        prefs.setBaseUrl(baseUrl.getText().toString().trim());
        prefs.setModel(model.getText().toString().trim());
        prefs.setApiKey(apiKey.getText().toString().trim());
        prefs.setOcrLangs(ocrLangs.getText().toString().trim());
        prefs.setApiType(apiType.getCheckedRadioButtonId() == R.id.type_openai ? "openai" : "ollama");
    }

    private void ping() {
        save();
        Toast.makeText(this, "接続確認中…", Toast.LENGTH_SHORT).show();
        AppExecutors.io(() -> {
            boolean ok = new LlmClient(prefs).ping();
            AppExecutors.main(() -> Toast.makeText(this,
                    ok ? "接続 OK" : "接続できません（サーバ/URL を確認）",
                    Toast.LENGTH_LONG).show());
        });
    }

    private void listModels() {
        save();
        AppExecutors.io(() -> {
            try {
                List<String> models = new LlmClient(prefs).listModels();
                AppExecutors.main(() -> showModelPicker(models));
            } catch (LlmClient.LlmException e) {
                AppExecutors.main(() -> Toast.makeText(this,
                        "取得失敗: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showModelPicker(List<String> models) {
        if (models.isEmpty()) {
            Toast.makeText(this, "モデルがありません", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = models.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("モデルを選択")
                .setItems(arr, (d, which) -> {
                    model.setText(arr[which]);
                    prefs.setModel(arr[which]);
                })
                .show();
    }

    private void downloadOcr() {
        save();
        List<String> langs = OcrModelManager.parseLangs(prefs.ocrLangs());
        if (langs.isEmpty()) {
            Toast.makeText(this, "OCR 言語を入力してください（例: jpn+eng）", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "OCR モデルを準備中…", Toast.LENGTH_SHORT).show();
        AppExecutors.io(() -> {
            OcrModelManager mm = new OcrModelManager(this);
            List<String> missing = mm.ensureLanguages(langs);
            AppExecutors.main(() -> Toast.makeText(this,
                    missing.isEmpty() ? "OCR モデル準備完了" : "取得失敗: " + missing,
                    Toast.LENGTH_LONG).show());
        });
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "オーバーレイ権限は許可済みです", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }
}
