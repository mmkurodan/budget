package com.micklab.budget.capture;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * MediaProjection の同意ダイアログだけを担う透明な Activity。
 * フロートボタンから起動され、許可されたトークンを {@link ScreenCaptureService} へ渡す。
 */
public class CaptureActivity extends AppCompatActivity {

    private final ActivityResultLauncher<Intent> consent =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, ScreenCaptureService.class);
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
        if (savedInstanceState == null) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            consent.launch(mpm.createScreenCaptureIntent());
        }
    }
}
