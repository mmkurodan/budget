package com.micklab.budget.ocr;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * フロートボタンの連続取得でためた OCR 結果を保持する簡易ストア。
 * 取得時に追記し、アプリに戻った際に一括で取り出して後続処理へ渡す。
 */
public final class OcrInbox {

    private static final List<String> ITEMS = Collections.synchronizedList(new ArrayList<>());

    private OcrInbox() {
    }

    public static void add(String text) {
        if (text != null && !text.trim().isEmpty()) {
            ITEMS.add(text.trim());
        }
    }

    public static int size() {
        return ITEMS.size();
    }

    /** ためた OCR 結果を改行連結で取り出し、クリアする。 */
    public static String drainJoined() {
        synchronized (ITEMS) {
            String joined = TextUtils.join("\n", ITEMS);
            ITEMS.clear();
            return joined;
        }
    }

    public static void clear() {
        ITEMS.clear();
    }
}
