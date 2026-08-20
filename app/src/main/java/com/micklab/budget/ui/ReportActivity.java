package com.micklab.budget.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.micklab.budget.R;
import com.micklab.budget.data.BudgetRepository;
import com.micklab.budget.data.CategorySum;
import com.micklab.budget.util.AppExecutors;
import com.micklab.budget.util.DateUtil;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 指定期間のカテゴリ別収支・合計を表示する。 */
public class ReportActivity extends AppCompatActivity {

    private BudgetRepository repo;
    private ReportAdapter adapter;

    private Button fromButton;
    private Button toButton;
    private TextView totalsView;

    private String from;
    private String to;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repo = new BudgetRepository(this);
        adapter = new ReportAdapter();

        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        fromButton = findViewById(R.id.btn_from);
        toButton = findViewById(R.id.btn_to);
        totalsView = findViewById(R.id.totals);

        from = DateUtil.firstOfMonth();
        to = DateUtil.lastOfMonth();
        updateDateButtons();

        fromButton.setOnClickListener(v -> pickDate(true));
        toButton.setOnClickListener(v -> pickDate(false));

        recompute();
    }

    private void updateDateButtons() {
        fromButton.setText(from);
        toButton.setText(to);
    }

    private void pickDate(boolean isFrom) {
        Calendar c = DateUtil.parse(isFrom ? from : to);
        new DatePickerDialog(this, (view, year, month, day) -> {
            String picked = DateUtil.iso(year, month, day);
            if (isFrom) from = picked;
            else to = picked;
            updateDateButtons();
            recompute();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void recompute() {
        AppExecutors.io(() -> {
            List<CategorySum> sums = repo.getCategorySums(from, to);
            BudgetRepository.Totals t = repo.getTotals(from, to);
            AppExecutors.main(() -> {
                adapter.setRows(sums);
                totalsView.setText(String.format(Locale.US,
                        "収入 +%,d 円   支出 %,d 円   収支 %,d 円",
                        t.income, t.expense, t.net));
            });
        });
    }
}
