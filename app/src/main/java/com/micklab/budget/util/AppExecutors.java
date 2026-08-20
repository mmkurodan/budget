package com.micklab.budget.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ごく薄いスレッド振り分けヘルパ。DB アクセスは単一スレッドに直列化して
 * SQLite の同時アクセス問題を避け、UI 更新はメインスレッドへ戻す。
 */
public final class AppExecutors {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    /** バックグラウンド（直列）で実行する。 */
    public static void io(Runnable task) {
        IO.execute(task);
    }

    /** メインスレッドで実行する。 */
    public static void main(Runnable task) {
        MAIN.post(task);
    }
}
