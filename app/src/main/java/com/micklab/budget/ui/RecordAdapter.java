package com.micklab.budget.ui;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.micklab.budget.R;
import com.micklab.budget.data.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * テーブル行を直接編集できる RecyclerView アダプタ。
 * 日付（タップでピッカー）/ カテゴリ（スピナー・ブランク可）/ 費目 / 金額（符号付き）/ 削除。
 * 編集はフォーカス喪失・選択変更時に {@link Callbacks#onRecordChanged} で永続化する。
 */
public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.VH> {

    /** スピナーのブランク表示。 */
    public static final String BLANK_LABEL = "（なし）";

    public interface Callbacks {
        void onRecordChanged(Record r);

        void onRecordDeleted(Record r, int position);

        void onPickDate(Record r, int position);
    }

    private final List<Record> items = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final Callbacks callbacks;

    public RecordAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
        setHasStableIds(true);
    }

    public void setItems(List<Record> records) {
        items.clear();
        items.addAll(records);
        notifyDataSetChanged();
    }

    public void setCategories(List<String> cats) {
        categories.clear();
        categories.addAll(cats);
        notifyDataSetChanged();
    }

    public List<Record> items() {
        return items;
    }

    public void addAtTop(Record r) {
        items.add(0, r);
        notifyItemInserted(0);
    }

    public void removeAt(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
    }

    public void insertAt(int position, Record r) {
        int p = Math.max(0, Math.min(position, items.size()));
        items.add(p, r);
        notifyItemInserted(p);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_record, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Record r = items.get(position);
        h.bound = r;
        h.binding = true;

        h.date.setText(r.date);

        // カテゴリのスピナー（ブランク + 定義 + 行固有の未定義カテゴリ）
        List<String> opts = new ArrayList<>();
        opts.add(BLANK_LABEL);
        opts.addAll(categories);
        if (!TextUtils.isEmpty(r.category) && !categories.contains(r.category)) {
            opts.add(r.category);
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                h.itemView.getContext(), android.R.layout.simple_spinner_item, opts);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        h.category.setAdapter(spinnerAdapter);
        int sel = TextUtils.isEmpty(r.category) ? 0 : opts.indexOf(r.category);
        h.category.setSelection(Math.max(0, sel));

        h.item.setText(r.item);
        h.amount.setText(r.amount == 0 ? "" : String.valueOf(r.amount));
        applyAmountColor(h.amount, r.amount);

        h.binding = false;
    }

    private void applyAmountColor(TextView tv, long amount) {
        if (amount < 0) {
            tv.setTextColor(Color.parseColor("#C62828")); // 支出=赤
        } else if (amount > 0) {
            tv.setTextColor(Color.parseColor("#2E7D32")); // 収入=緑
        } else {
            tv.setTextColor(0xFF888888);
        }
    }

    class VH extends RecyclerView.ViewHolder {
        final TextView date;
        final Spinner category;
        final EditText item;
        final EditText amount;
        final ImageButton delete;

        Record bound;
        boolean binding;

        VH(@NonNull View v) {
            super(v);
            date = v.findViewById(R.id.row_date);
            category = v.findViewById(R.id.row_category);
            item = v.findViewById(R.id.row_item);
            amount = v.findViewById(R.id.row_amount);
            delete = v.findViewById(R.id.row_delete);

            date.setOnClickListener(view -> {
                if (bound != null) callbacks.onPickDate(bound, getBindingAdapterPosition());
            });

            category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (binding || bound == null) return;
                    String value = pos == 0 ? "" : String.valueOf(parent.getItemAtPosition(pos));
                    if (value.equals(bound.category)) return; // 再バインド由来の発火を無視
                    bound.category = value;
                    callbacks.onRecordChanged(bound);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            item.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus || binding || bound == null) return;
                String v2 = item.getText().toString().trim();
                if (!v2.equals(bound.item)) {
                    bound.item = v2;
                    callbacks.onRecordChanged(bound);
                }
            });

            amount.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus || binding || bound == null) return;
                long parsed = parseAmount(amount.getText().toString());
                if (parsed != bound.amount) {
                    bound.amount = parsed;
                    callbacks.onRecordChanged(bound);
                }
                amount.setText(parsed == 0 ? "" : String.valueOf(parsed));
                applyAmountColor(amount, parsed);
            });

            delete.setOnClickListener(view -> {
                if (bound != null) callbacks.onRecordDeleted(bound, getBindingAdapterPosition());
            });
        }
    }

    /** カンマ・通貨記号・全角を落として符号付き整数に。解析不能は 0。 */
    static long parseAmount(String s) {
        if (s == null) return 0;
        String t = s.replace(",", "").replace("¥", "").replace("￥", "")
                .replace("円", "").replace("＋", "+").replace("−", "-")
                .replace("－", "-").trim();
        // 全角数字を半角へ
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        t = sb.toString();
        if (t.isEmpty() || "-".equals(t) || "+".equals(t)) return 0;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String format(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }
}
