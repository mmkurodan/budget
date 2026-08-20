package com.micklab.budget.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.micklab.budget.R;
import com.micklab.budget.capture.CaptureActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 他アプリ上に重ねるフロートボタンを保持する常駐サービス。
 * ボタンをタップすると {@link CaptureActivity} を起動し、画面キャプチャ→OCR→LLM 登録へ進む。
 * ドラッグで移動できる。
 */
public class FloatingButtonService extends Service {

    private static final String CHANNEL_ID = "budget_overlay";
    private static final int NOTIF_ID = 1001;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private WindowManager windowManager;
    private View buttonView;
    private WindowManager.LayoutParams params;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING.getAndSet(true)) {
            return START_STICKY; // 既に表示済み
        }
        startForegroundNotification();
        addOverlayButton();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "フロートボタン", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("家計簿 フロートボタン")
                .setContentText("タップで画面を取り込み、明細を一括登録します")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, NOTIF_ID, n,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }

    private void addOverlayButton() {
        int size = dp(56);
        int pad = dp(12);

        ImageView button = new ImageView(this);
        button.setImageResource(android.R.drawable.ic_menu_camera);
        button.setBackgroundResource(R.drawable.overlay_button_bg);
        button.setColorFilter(Color.WHITE);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription("画面を取り込む");

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);

        button.setOnTouchListener(new DragTouchListener());
        button.setOnClickListener(v -> onButtonTapped());

        windowManager.addView(button, params);
        buttonView = button;
    }

    private void onButtonTapped() {
        Intent i = new Intent(this, CaptureActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** 移動と（移動でない場合の）クリックを区別する。 */
    private class DragTouchListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float touchX;
        private float touchY;
        private boolean moved;
        private final int slop = ViewConfiguration.get(FloatingButtonService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    moved = false;
                    return false; // クリック検出のため下流にも渡す
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - touchX);
                    int dy = (int) (e.getRawY() - touchY);
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true;
                    }
                    params.x = initialX + dx;
                    params.y = initialY + dy;
                    windowManager.updateViewLayout(buttonView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (buttonView != null && windowManager != null) {
            try {
                windowManager.removeView(buttonView);
            } catch (Exception ignored) {
            }
            buttonView = null;
        }
        RUNNING.set(false);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
