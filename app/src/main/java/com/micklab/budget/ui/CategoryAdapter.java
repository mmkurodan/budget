package com.micklab.budget.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.micklab.budget.R;

import java.util.ArrayList;
import java.util.List;

/** カテゴリ一覧（改名 / 削除）。 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.VH> {

    public interface Callbacks {
        void onRename(String name);

        void onDelete(String name);
    }

    private final List<String> names = new ArrayList<>();
    private final Callbacks callbacks;

    public CategoryAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setNames(List<String> list) {
        names.clear();
        names.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return names.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_category, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String name = names.get(position);
        h.name.setText(name);
        h.edit.setOnClickListener(v -> callbacks.onRename(name));
        h.delete.setOnClickListener(v -> callbacks.onDelete(name));
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final ImageButton edit;
        final ImageButton delete;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.cat_name);
            edit = v.findViewById(R.id.cat_edit);
            delete = v.findViewById(R.id.cat_delete);
        }
    }
}
