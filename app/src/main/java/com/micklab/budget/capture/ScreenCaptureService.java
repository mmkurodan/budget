package com.micklab.budget.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.IntentCompat;

import com.micklab.budget.ui.ImportPreviewActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MediaProjection で画面を1枚だけキャプチャし、保存した画像で一括登録プレビューを開く。
 * Android 14+ の要件に合わせ、mediaProjection 型のフォアグラウンド起動 → トークン取得 →
 * コールバック登録 → VirtualDisplay 作成、の順で処理する。
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "budget_capture";
    private static final int NOTIF_ID = 1002;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private final AtomicBoolean captured = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (intent == null) {
            stopEverything();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        if (data == null) {
            fail("キャプチャ情報がありません");
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            fail("画面キャプチャを開始できません");
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        // Android 14+ は VirtualDisplay 作成前のコールバック登録が必須。
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped");
            }
        }, handler);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int width = dm.widthPixels;
        final int height = dm.heightPixels;
        int dpi = dm.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("budget-capture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);

        imageReader.setOnImageAvailableListener(reader -> onImage(reader, width, height), handler);

        // フレームが来ない場合の保険
        handler.postDelayed(() -> {
            if (!captured.get()) {
                fail("画面を取得できませんでした");
            }
        }, 6000);

        return START_NOT_STICKY;
    }

    private void onImage(ImageReader reader, int width, int height) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            if (captured.getAndSet(true)) return;

            Bitmap bitmap = toBitmap(image, width, height);
            File file = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();

            Intent preview = new Intent(this, ImportPreviewActivity.class);
            preview.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            preview.putExtra(ImportPreviewActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
            startActivity(preview);
        } catch (Exception e) {
            Log.e(TAG, "capture failed", e);
        } finally {
            if (image != null) image.close();
            stopEverything();
        }
    }

    private Bitmap toBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int bmpWidth = width + (pixelStride == 0 ? 0 : rowPadding / pixelStride);

        Bitmap full = Bitmap.createBitmap(Math.max(bmpWidth, width), height, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        if (bmpWidth != width) {
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, width, height);
            full.recycle();
            return cropped;
        }
        return full;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "画面キャプチャ", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("家計簿")
                .setContentText("画面を取り込んでいます…")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, n, type);
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopEverything();
    }

    private void stopEverything() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
