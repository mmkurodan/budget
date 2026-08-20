package com.micklab.budget.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * レコード / カテゴリの CRUD とレポート集計。すべて同期実行なので、呼び出し側は
 * {@link com.micklab.budget.util.AppExecutors#io} 上で実行すること。
 */
public class BudgetRepository {

    /** 集計不能なブランクカテゴリの表示名。 */
    public static final String UNCATEGORIZED = "(未分類)";

    private final BudgetDbHelper helper;

    public BudgetRepository(Context context) {
        this.helper = new BudgetDbHelper(context);
    }

    // ---- records ----------------------------------------------------------

    public List<Record> getAllRecords() {
        List<Record> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(BudgetDbHelper.T_RECORDS, null, null, null, null, null,
                BudgetDbHelper.C_DATE + " DESC, " + BudgetDbHelper.C_ID + " DESC");
        try {
            while (c.moveToNext()) {
                out.add(readRecord(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private Record readRecord(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(c.getColumnIndexOrThrow(BudgetDbHelper.C_ID));
        r.date = c.getString(c.getColumnIndexOrThrow(BudgetDbHelper.C_DATE));
        r.category = orEmpty(c.getString(c.getColumnIndexOrThrow(BudgetDbHelper.C_CATEGORY)));
        r.item = orEmpty(c.getString(c.getColumnIndexOrThrow(BudgetDbHelper.C_ITEM)));
        r.amount = c.getLong(c.getColumnIndexOrThrow(BudgetDbHelper.C_AMOUNT));
        return r;
    }

    public long insertRecord(Record r) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insert(BudgetDbHelper.T_RECORDS, null, toValues(r));
        r.id = id;
        return id;
    }

    public void updateRecord(Record r) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.update(BudgetDbHelper.T_RECORDS, toValues(r),
                BudgetDbHelper.C_ID + "=?", new String[]{String.valueOf(r.id)});
    }

    public void deleteRecord(long id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(BudgetDbHelper.T_RECORDS, BudgetDbHelper.C_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    private ContentValues toValues(Record r) {
        ContentValues cv = new ContentValues();
        cv.put(BudgetDbHelper.C_DATE, r.date);
        cv.put(BudgetDbHelper.C_CATEGORY, r.category == null ? "" : r.category);
        cv.put(BudgetDbHelper.C_ITEM, r.item == null ? "" : r.item);
        cv.put(BudgetDbHelper.C_AMOUNT, r.amount);
        if (r.id == 0) {
            cv.put(BudgetDbHelper.C_CREATED, System.currentTimeMillis());
        }
        return cv;
    }

    // ---- categories -------------------------------------------------------

    public List<String> getCategoryNames() {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(BudgetDbHelper.T_CATEGORIES,
                new String[]{BudgetDbHelper.CC_NAME}, null, null, null, null,
                BudgetDbHelper.CC_ORDER + " ASC, " + BudgetDbHelper.CC_ID + " ASC");
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public boolean categoryExists(String name) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query(BudgetDbHelper.T_CATEGORIES, new String[]{BudgetDbHelper.CC_ID},
                BudgetDbHelper.CC_NAME + "=?", new String[]{name}, null, null, null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** 追加。空文字/既存は無視して -1 を返す。成功時は行 id。 */
    public long addCategory(String name) {
        if (name == null) return -1;
        String n = name.trim();
        if (TextUtils.isEmpty(n) || categoryExists(n)) return -1;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(BudgetDbHelper.CC_NAME, n);
        cv.put(BudgetDbHelper.CC_ORDER, nextSortOrder(db));
        return db.insert(BudgetDbHelper.T_CATEGORIES, null, cv);
    }

    private int nextSortOrder(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COALESCE(MAX(" + BudgetDbHelper.CC_ORDER + "), -1) + 1 FROM "
                + BudgetDbHelper.T_CATEGORIES, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 改名。使用中レコードのカテゴリ名も追従して更新する。改名先が既存なら統合する。
     */
    public boolean renameCategory(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String nn = newName.trim();
        if (TextUtils.isEmpty(nn) || oldName.equals(nn)) return false;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues rc = new ContentValues();
            rc.put(BudgetDbHelper.C_CATEGORY, nn);
            db.update(BudgetDbHelper.T_RECORDS, rc,
                    BudgetDbHelper.C_CATEGORY + "=?", new String[]{oldName});

            if (categoryExists(nn)) {
                // 統合: 旧名の定義行は削除
                db.delete(BudgetDbHelper.T_CATEGORIES,
                        BudgetDbHelper.CC_NAME + "=?", new String[]{oldName});
            } else {
                ContentValues cc = new ContentValues();
                cc.put(BudgetDbHelper.CC_NAME, nn);
                db.update(BudgetDbHelper.T_CATEGORIES, cc,
                        BudgetDbHelper.CC_NAME + "=?", new String[]{oldName});
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** カテゴリ定義を削除する。レコード側の文字列はそのまま残す（履歴を壊さない）。 */
    public void deleteCategory(String name) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(BudgetDbHelper.T_CATEGORIES,
                BudgetDbHelper.CC_NAME + "=?", new String[]{name});
    }

    // ---- report -----------------------------------------------------------

    /** 期間内のカテゴリ別収支（符号付き総和）。ブランクは "(未分類)" にまとめる。 */
    public List<CategorySum> getCategorySums(String from, String to) {
        List<CategorySum> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        String sql = "SELECT CASE WHEN " + BudgetDbHelper.C_CATEGORY + " IS NULL OR "
                + BudgetDbHelper.C_CATEGORY + "='' THEN ? ELSE " + BudgetDbHelper.C_CATEGORY
                + " END AS cat, "
                + "SUM(" + BudgetDbHelper.C_AMOUNT + ") AS s, COUNT(*) AS c "
                + "FROM " + BudgetDbHelper.T_RECORDS + " "
                + "WHERE " + BudgetDbHelper.C_DATE + ">=? AND " + BudgetDbHelper.C_DATE + "<=? "
                + "GROUP BY cat ORDER BY s ASC";
        Cursor c = db.rawQuery(sql, new String[]{UNCATEGORIZED, from, to});
        try {
            while (c.moveToNext()) {
                out.add(new CategorySum(c.getString(0), c.getLong(1), c.getInt(2)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 期間内の合計（収支・収入・支出）。 */
    public Totals getTotals(String from, String to) {
        SQLiteDatabase db = helper.getReadableDatabase();
        String sql = "SELECT COALESCE(SUM(" + BudgetDbHelper.C_AMOUNT + "),0), "
                + "COALESCE(SUM(CASE WHEN " + BudgetDbHelper.C_AMOUNT + ">0 THEN "
                + BudgetDbHelper.C_AMOUNT + " ELSE 0 END),0), "
                + "COALESCE(SUM(CASE WHEN " + BudgetDbHelper.C_AMOUNT + "<0 THEN "
                + BudgetDbHelper.C_AMOUNT + " ELSE 0 END),0) "
                + "FROM " + BudgetDbHelper.T_RECORDS + " "
                + "WHERE " + BudgetDbHelper.C_DATE + ">=? AND " + BudgetDbHelper.C_DATE + "<=?";
        Cursor c = db.rawQuery(sql, new String[]{from, to});
        try {
            if (c.moveToFirst()) {
                return new Totals(c.getLong(0), c.getLong(1), c.getLong(2));
            }
        } finally {
            c.close();
        }
        return new Totals(0, 0, 0);
    }

    /** 収支・収入・支出（支出は負値）。 */
    public static class Totals {
        public final long net;
        public final long income;
        public final long expense;

        public Totals(long net, long income, long expense) {
            this.net = net;
            this.income = income;
            this.expense = expense;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
