package com.micklab.budget.data;

/**
 * 家計簿の1レコード。列は 日付・カテゴリ・費目・金額。
 * <p>金額は符号付き（支出=負, 収入=正）で保持し、レポートの収支合計を単純な総和で
 * 計算できるようにする。カテゴリはブランク（空文字）を許容する。
 */
public class Record {

    public long id;         // 0 = 未保存
    public String date;     // "yyyy-MM-dd"
    public String category; // ブランク可（"" 相当）
    public String item;     // 費目 / 摘要
    public long amount;     // 円（符号付き）

    public Record() {
        this.category = "";
        this.item = "";
    }

    public Record(long id, String date, String category, String item, long amount) {
        this.id = id;
        this.date = date;
        this.category = category == null ? "" : category;
        this.item = item == null ? "" : item;
        this.amount = amount;
    }

    public boolean isIncome() {
        return amount > 0;
    }
}
