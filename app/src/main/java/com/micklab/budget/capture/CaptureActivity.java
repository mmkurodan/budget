package com.micklab.budget.capture;

import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * MediaProjection の同意ダイアログだけを担う透明な Activity。モードにより取得範囲を切り替える。
 * <ul>
 *   <li>{@link #MODE_CONTINUOUS}: 全画面固定（{@code createConfigForDefaultDisplay}）。フロートボタン用。</li>
 *   <li>{@link #MODE_ONESHOT}: 全画面／単一アプリを選択可（{@code createConfigForUserChoice}）。
 *       アプリ内ボタン用（取得対象アプリを個別指定）。</li>
 * </ul>
 * Android 14 未満は選択肢なしの既定インテントにフォールバックする。
 */
public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "capture_mode";
    public static final String MODE_CONTINUOUS = "continuous";
    public static final String MODE_ONESHOT = "oneshot";

    private String mode = MODE_CONTINUOUS;

    private final ActivityResultLauncher<Intent> consent =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, ScreenCaptureService.class);
                    svc.setAction(ScreenCaptureService.ACTION_START);
                    svc.putExtra(EXTRA_MODE, mode);
                    svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                    svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData());
                    ContextCompat.startForegroundService(this, svc);
                } else {
                    Toast.makeText(this, "画面キャプチャがキャンセルされました", Toast.LENGTH_SHORT).show();
                }
                finish();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String requested = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE);
        if (requested != null) mode = requested;

        if (savedInstanceState == null) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            consent.launch(buildCaptureIntent(mpm));
        }
    }

    private Intent buildCaptureIntent(MediaProjectionManager mpm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MediaProjectionConfig config = MODE_ONESHOT.equals(mode)
                    ? MediaProjectionConfig.createConfigForUserChoice()     // 全画面 or 単一アプリ
                    : MediaProjectionConfig.createConfigForDefaultDisplay(); // 全画面固定
            return mpm.createScreenCaptureIntent(config);
        }
        return mpm.createScreenCaptureIntent();
    }
}
