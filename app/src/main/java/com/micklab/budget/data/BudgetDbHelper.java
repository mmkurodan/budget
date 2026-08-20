package com.micklab.budget.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite スキーマ。テーブルは 日付・カテゴリ・費目・金額 のレコード表と、
 * 任意定義できるカテゴリ表の2つ。初回作成時に既定カテゴリ4件を投入する。
 */
public class BudgetDbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "budget.db";
    public static final int DB_VERSION = 1;

    // records
    public static final String T_RECORDS = "records";
    public static final String C_ID = "_id";
    public static final String C_DATE = "date";        // "yyyy-MM-dd"
    public static final String C_CATEGORY = "category"; // ブランク可
    public static final String C_ITEM = "item";        // 費目
    public static final String C_AMOUNT = "amount";    // 符号付き円
    public static final String C_CREATED = "created_at";

    // categories
    public static final String T_CATEGORIES = "categories";
    public static final String CC_ID = "_id";
    public static final String CC_NAME = "name";
    public static final String CC_ORDER = "sort_order";

    /** 初期値のカテゴリ。 */
    public static final String[] DEFAULT_CATEGORIES = {"固定費", "変動費", "収入", "食品"};

    public BudgetDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_RECORDS + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_DATE + " TEXT NOT NULL, "
                + C_CATEGORY + " TEXT, "
                + C_ITEM + " TEXT, "
                + C_AMOUNT + " INTEGER NOT NULL DEFAULT 0, "
                + C_CREATED + " INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE INDEX idx_records_date ON " + T_RECORDS + "(" + C_DATE + ")");

        db.execSQL("CREATE TABLE " + T_CATEGORIES + " ("
                + CC_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + CC_NAME + " TEXT NOT NULL UNIQUE, "
                + CC_ORDER + " INTEGER NOT NULL DEFAULT 0)");

        for (int i = 0; i < DEFAULT_CATEGORIES.length; i++) {
            ContentValues cv = new ContentValues();
            cv.put(CC_NAME, DEFAULT_CATEGORIES[i]);
            cv.put(CC_ORDER, i);
            db.insert(T_CATEGORIES, null, cv);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 のみ。将来のスキーマ変更時はマイグレーションをここに追加する。
        db.execSQL("DROP TABLE IF EXISTS " + T_RECORDS);
        db.execSQL("DROP TABLE IF EXISTS " + T_CATEGORIES);
        onCreate(db);
    }
}
