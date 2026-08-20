package com.micklab.budget.data;

/** レポート用: 1カテゴリの収支合計と件数。 */
public class CategorySum {

    public final String category; // 表示名（ブランクは "(未分類)"）
    public final long sum;        // 収支合計（符号付き）
    public final int count;

    public CategorySum(String category, long sum, int count) {
        this.category = category;
        this.sum = sum;
        this.count = count;
    }
}
