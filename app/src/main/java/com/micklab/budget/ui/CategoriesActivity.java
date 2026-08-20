package com.micklab.budget.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.micklab.budget.R;
import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.util.AppExecutors;

import java.util.List;

/** カテゴリの任意定義（追加 / 改名 / 削除）。初期値は 固定費・変動費・収入・食品。 */
public class CategoriesActivity extends AppCompatActivity implements CategoryAdapter.Callbacks {

    private BudgetRepository repo;
    private CategoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_categories);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repo = new BudgetRepository(this);
        adapter = new CategoryAdapter(this);

        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showAddDialog());

        reload();
    }

    private void reload() {
        AppExecutors.io(() -> {
            List<String> names = repo.getCategoryNames();
            AppExecutors.main(() -> adapter.setNames(names));
        });
    }

    private void showAddDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("カテゴリ名");
        new AlertDialog.Builder(this)
                .setTitle("カテゴリを追加")
                .setView(input)
                .setPositiveButton("追加", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    AppExecutors.io(() -> {
                        long id = repo.addCategory(name);
                        AppExecutors.main(() -> {
                            if (id < 0) {
                                Toast.makeText(this, "既に存在します", Toast.LENGTH_SHORT).show();
                            }
                            reload();
                        });
                    });
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    public void onRename(String oldName) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(oldName);
        new AlertDialog.Builder(this)
                .setTitle("カテゴリを改名")
                .setMessage("使用中のレコードにも反映されます")
                .setView(input)
                .setPositiveButton("変更", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    AppExecutors.io(() -> {
                        repo.renameCategory(oldName, newName);
                        AppExecutors.main(this::reload);
                    });
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    public void onDelete(String name) {
        new AlertDialog.Builder(this)
                .setTitle("カテゴリを削除")
                .setMessage("「" + name + "」を一覧から削除します。\n（既存レコードの表記はそのまま残ります）")
                .setPositiveButton("削除", (d, w) -> AppExecutors.io(() -> {
                    repo.deleteCategory(name);
                    AppExecutors.main(this::reload);
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }
}
