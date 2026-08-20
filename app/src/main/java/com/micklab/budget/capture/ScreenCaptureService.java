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
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.IntentCompat;

import com.micklab.budget.llm.ImportManager;
import com.micklab.budget.ocr.OcrInbox;
import com.micklab.budget.overlay.FloatingButtonService;
import com.micklab.budget.ui.ImportPreviewActivity;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MediaProjection で画面を取得し OCR する。2 モード:
 * <ul>
 *   <li><b>連続（全画面）</b>: フロートボタン用。セッションを保持し、タップごとに全画面を1枚 OCR
 *       して {@link OcrInbox} にため、アプリに戻った時にまとめて後続処理へ渡す。</li>
 *   <li><b>単発（対象アプリ個別指定）</b>: アプリ内ボタン用。ユーザーが選んだ単一アプリ（または全画面）を
 *       1枚だけ OCR し、そのままプレビューを開く。</li>
 * </ul>
 * Android 14+ の順序（mediaProjection 型で前景化 → トークン取得 → コールバック登録 →
 * VirtualDisplay 作成）に従う。
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "budget_capture";
    private static final int NOTIF_ID = 1002;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public static final String ACTION_START = "com.micklab.budget.capture.START";
    public static final String ACTION_CAPTURE = "com.micklab.budget.capture.CAPTURE";
    public static final String ACTION_STOP = "com.micklab.budget.capture.STOP";

    private static final AtomicBoolean PROJECTING = new AtomicBoolean(false);

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private Image latestImage;
    private ImportManager importManager;
    private int width;
    private int height;
    private boolean oneShot; // true=単発（対象アプリ個別指定）, false=連続（全画面）

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isProjecting() {
        return PROJECTING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        if (ACTION_CAPTURE.equals(action)) {
            if (projection != null && handler != null) {
                handler.post(() -> doCapture(0));
            }
            return START_NOT_STICKY;
        }

        // ACTION_START（または null）: セッション未確立なら初期化して1枚取得
        if (projection == null) {
            oneShot = CaptureActivity.MODE_ONESHOT.equals(
                    intent == null ? null : intent.getStringExtra(CaptureActivity.EXTRA_MODE));
            if (!setupProjection(intent)) {
                fail("画面キャプチャを開始できません");
                return START_NOT_STICKY;
            }
        }
        handler.post(() -> doCapture(0));
        return START_NOT_STICKY;
    }

    private boolean setupProjection(Intent intent) {
        if (intent == null) return false;
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        if (data == null) return false;

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) return false;

        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped");
            }
        }, handler);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        width = dm.widthPixels;
        height = dm.heightPixels;
        int dpi = dm.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        virtualDisplay = projection.createVirtualDisplay("budget-capture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);

        imageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img == null) return;
            if (latestImage != null) latestImage.close();
            latestImage = img; // 常に最新フレームを保持（onデマンドで変換）
        }, handler);

        importManager = new ImportManager(this);
        PROJECTING.set(true);
        return true;
    }

    /**
     * 最新フレームを取得して OCR する。連続時はオーバーレイを一瞬隠して inbox にため、
     * 単発時はそのままプレビューを開いてセッションを終える。フレーム未到達時は少し待って再試行。
     */
    private void doCapture(int attempt) {
        if (!oneShot && attempt == 0) FloatingButtonService.setOverlayVisible(false);
        handler.postDelayed(() -> {
            Image img = latestImage;
            if (img != null) {
                try {
                    Bitmap bmp = toBitmap(img);
                    String text = importManager.recognize(bmp);
                    bmp.recycle();
                    if (oneShot) {
                        if (text.isEmpty()) {
                            toast("文字を認識できませんでした");
                        } else {
                            openPreview(text);
                        }
                    } else {
                        if (text.isEmpty()) {
                            toast("文字を認識できませんでした");
                        } else {
                            OcrInbox.add(text);
                            toast("取得しました（計 " + OcrInbox.size() + " 件）");
                        }
                        updateNotification();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "capture/ocr failed", e);
                    toast("OCR 失敗: " + e.getMessage());
                } finally {
                    if (oneShot) {
                        stopEverything();
                    } else {
                        FloatingButtonService.setOverlayVisible(true);
                    }
                }
            } else if (attempt < 8) {
                doCapture(attempt + 1);
            } else {
                if (oneShot) {
                    toast("画面を取得できませんでした");
                    stopEverything();
                } else {
                    FloatingButtonService.setOverlayVisible(true);
                    toast("画面を取得できませんでした");
                }
            }
        }, 250);
    }

    private void openPreview(String text) {
        Intent i = new Intent(this, ImportPreviewActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(ImportPreviewActivity.EXTRA_OCR_TEXT, text);
        startActivity(i);
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
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

    // ---- notification -----------------------------------------------------

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "画面キャプチャ", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type);
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        String text = oneShot
                ? "選択したアプリを取得しています…"
                : "ためた OCR: " + OcrInbox.size() + " 件。アプリに戻ると登録候補にします";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("家計簿 画面取得")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void toast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ---- lifecycle --------------------------------------------------------

    private void fail(String message) {
        toast(message);
        stopEverything();
    }

    private void stopEverything() {
        teardownResources();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void teardownResources() {
        PROJECTING.set(false);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (latestImage != null) {
            latestImage.close();
            latestImage = null;
        }
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
        FloatingButtonService.setOverlayVisible(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        teardownResources();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
