package com.micklab.budget.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.micklab.budget.R;
import com.micklab.budget.data.CategorySum;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** レポートのカテゴリ別収支行（収支バー付き）。 */
public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.VH> {

    private final List<CategorySum> rows = new ArrayList<>();
    private long maxAbs = 1;

    public void setRows(List<CategorySum> list) {
        rows.clear();
        rows.addAll(list);
        maxAbs = 1;
        for (CategorySum cs : list) {
            maxAbs = Math.max(maxAbs, Math.abs(cs.sum));
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_report, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CategorySum cs = rows.get(position);
        h.name.setText(cs.category);
        h.count.setText(String.format(Locale.US, "%d件", cs.count));
        h.sum.setText(String.format(Locale.US, "%,d", cs.sum));
        h.sum.setTextColor(cs.sum < 0 ? Color.parseColor("#C62828")
                : cs.sum > 0 ? Color.parseColor("#2E7D32") : 0xFF888888);
        int pct = (int) (Math.abs(cs.sum) * 100L / maxAbs);
        h.bar.setProgress(Math.max(0, Math.min(100, pct)));
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;
        final TextView sum;
        final ProgressBar bar;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.rep_name);
            count = v.findViewById(R.id.rep_count);
            sum = v.findViewById(R.id.rep_sum);
            bar = v.findViewById(R.id.rep_bar);
        }
    }
}
