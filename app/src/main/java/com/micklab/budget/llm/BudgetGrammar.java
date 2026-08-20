package com.micklab.budget.llm;

import java.util.List;

/**
 * LLM 出力を「家計簿レコードの JSON 配列」に厳密拘束する GBNF を生成する。
 * <p>llama.cpp の文法制約サンプラ（{@code grammar} フィールド）に渡すことで、
 * 形式を徹底させる。カテゴリは既知の定義（＋空文字）だけに限定する。
 *
 * <p>生成される JSON の形:
 * <pre>[{"date":"YYYY-MM-DD","category":"固定費","item":"…","amount":-1234}, …]</pre>
 */
public final class BudgetGrammar {

    private BudgetGrammar() {
    }

    // strchar の [^"\\] は「二重引用符とバックスラッシュ以外」を表す（テキストブロック内では \\\\）。
    private static final String TEMPLATE =
            """
            root ::= ws "[" ws ( record ( ws "," ws record )* )? ws "]" ws
            record ::= "{" ws dq "date" dq ws ":" ws date ws "," ws dq "category" dq ws ":" ws category ws "," ws dq "item" dq ws ":" ws string ws "," ws dq "amount" dq ws ":" ws integer ws "}"
            dq ::= ["]
            date ::= dq digit digit digit digit "-" digit digit "-" digit digit dq
            __CATEGORY__
            integer ::= "-"? ("0" | [1-9] digit*)
            string ::= dq strchar* dq
            strchar ::= [^"\\\\]
            digit ::= [0-9]
            ws ::= [ ]*
            """;

    /**
     * カテゴリ定義から GBNF を生成する。空文字カテゴリ（未分類）も常に許可する。
     */
    public static String build(List<String> categories) {
        StringBuilder alts = new StringBuilder();
        for (String c : categories) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (alts.length() > 0) alts.append(" | ");
            alts.append("(dq \"").append(escapeLiteral(t)).append("\" dq)");
        }
        // 空文字（ブランク）を許可
        if (alts.length() > 0) alts.append(" | ");
        alts.append("(dq dq)");

        String categoryRule = "category ::= " + alts;
        return TEMPLATE.replace("__CATEGORY__", categoryRule);
    }

    /** GBNF のダブルクオート文字列リテラルに安全に埋め込めるようエスケープする。 */
    private static String escapeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
