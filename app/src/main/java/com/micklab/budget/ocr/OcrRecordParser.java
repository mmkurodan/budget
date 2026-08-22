package com.micklab.budget.ocr;

import com.micklab.budget.data.Record;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR 結果を要素（空白区切りトークン）ごとに走査し、レコードへ整える後続処理。
 * <p>ルール:
 * <ul>
 *   <li>日付・費目・金額を登場順に認識する。</li>
 *   <li>金額で 7 桁を越える数字はスキップ（口座番号などの誤検出除け）。</li>
 *   <li>最初の日付が登場する以前の値は無視する。</li>
 *   <li>金額に符号が付いていれば反映（-, △, ▲, 括弧 は負）。無ければ正（表記どおり）。</li>
 * </ul>
 * カテゴリは付与しない（空）。LLM は使わない。
 */
public final class OcrRecordParser {

    private OcrRecordParser() {
    }

    // 日付のバリエーション（区切りは - / . いずれも可）:
    //  4桁年:  2026-08-01 / 2026/8/1 / 2026.08.01
    //  2桁年:  26-08-01 / 26/8/1 / 26.08.01
    //  年なし: 08-01 / 8/1 / 08.01
    //  和暦式: 2026年8月1日 / 26年8月1日 / 8月1日
    private static final Pattern DATE_Y4 = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DATE_Y2 = Pattern.compile("^(\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DATE_JP_Y = Pattern.compile("^(\\d{2,4})年(\\d{1,2})月(\\d{1,2})日?$");
    private static final Pattern DATE_MD = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DATE_JP_MD = Pattern.compile("^(\\d{1,2})月(\\d{1,2})日?$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * OCR の読み取り順を保ったまま、日付・費目・金額をそれぞれ登場順に集め、
     * 同じ順番同士（index）で1レコードに組み立てる。行/列どちらのレイアウトでも
     * 「文字が1つの費目に全結合」されないようにする。
     * <p>費目の区切りは 金額 と 行末。日付・金額はそれぞれ現れた順に並べる。金額の数だけ
     * レコードを作り、日付は同 index（無ければ直近＝カウントが少ない側は最後の日付を流用）、
     * 費目も同 index を割り当てる。日付が1つも無ければ空を返す。
     */
    /** 3列（日付・費目・金額）の候補。金額は符号付き整数の文字列（編集用）。長さは不揃いのことがある。 */
    public static class Columns {
        public final List<String> dates;
        public final List<String> items;
        public final List<String> amounts;

        public Columns(List<String> dates, List<String> items, List<String> amounts) {
            this.dates = dates;
            this.items = items;
            this.amounts = amounts;
        }
    }

    /**
     * OCR の読み取り順を保ったまま、日付・費目・金額をそれぞれ登場順に集めて3列で返す。
     * 費目の区切りは 金額 と 行末。除外ルール（残高＋直後の数字／7桁超／1文字）を適用する。
     * <p>各列の長さは不揃いのことがあり、マトリクス編集で揃えてから登録する。
     */
    public static Columns parseColumns(String text) {
        List<String> dates = new ArrayList<>();
        List<String> amounts = new ArrayList<>();
        List<String> items = new ArrayList<>();
        if (text == null) return new Columns(dates, items, amounts);
        int year = Calendar.getInstance().get(Calendar.YEAR);
        StringBuilder run = new StringBuilder();
        boolean skipNextNumber = false;
        boolean lastWasAmount = false; // 直前の要素が有効な金額だったか（符号1文字の反映用）

        for (String line : text.split("\\r?\\n")) {
            for (String raw : line.split("\\s+")) {
                String tok = raw.trim();
                if (tok.isEmpty()) continue;
                String half = toHalfWidth(tok);

                // 有効な数字の直後にある符号1文字（+ / -）は無視せず、直前の金額へ反映する。
                // 残高の値は既にスキップ済みなので lastWasAmount=false となり対象外。
                Boolean sign = signOnly(half);
                if (sign != null && lastWasAmount) {
                    applyTrailingSign(amounts, sign);
                    lastWasAmount = false; // 消費（同じ金額に二重適用しない）
                    continue;
                }
                if (tok.length() <= 1) { lastWasAmount = false; continue; } // 1文字・空は無視

                // 「残高」を含む要素は無視。数字が付いていなければ直後の数字も無視。
                if (tok.contains("残高")) {
                    skipNextNumber = !hasDigit(half);
                    lastWasAmount = false;
                    continue;
                }
                if (skipNextNumber) {
                    skipNextNumber = false;
                    if (parseAmount(half) != null) { lastWasAmount = false; continue; } // 残高の値を無視
                }

                String date = matchDate(half, year);
                if (date != null) {
                    dates.add(date);
                    lastWasAmount = false;
                    continue;
                }
                Amount amt = parseAmount(half);
                if (amt != null) {
                    if (amt.digits.length() > 7) { lastWasAmount = false; continue; } // 7桁超はスキップ
                    long v = Long.parseLong(amt.digits);
                    amounts.add(String.valueOf(amt.negative ? -v : v));
                    flushRun(run, items); // 金額で費目を区切る
                    lastWasAmount = true;
                    continue;
                }
                lastWasAmount = false;
                if (run.length() > 0) run.append(' ');
                run.append(tok);
            }
            flushRun(run, items); // 行末で費目を区切る
        }
        return new Columns(dates, items, amounts);
    }

    /**
     * 3列を同 index で1レコードに組み立てる（金額の数だけ。日付が足りなければ直近を流用）。
     * 日付が1つも無ければ空を返す。
     */
    public static List<Record> parse(String text) {
        Columns c = parseColumns(text);
        List<Record> out = new ArrayList<>();
        if (c.dates.isEmpty()) return out;
        for (int k = 0; k < c.amounts.size(); k++) {
            Record r = new Record();
            r.date = c.dates.get(Math.min(k, c.dates.size() - 1));
            r.category = "";
            r.item = k < c.items.size() ? c.items.get(k) : "";
            try {
                r.amount = Long.parseLong(c.amounts.get(k));
            } catch (NumberFormatException e) {
                r.amount = 0;
            }
            out.add(r);
        }
        return out;
    }

    private static void flushRun(StringBuilder run, List<String> items) {
        if (run.length() > 0) {
            items.add(run.toString().trim());
            run.setLength(0);
        }
    }

    /**
     * OCR テキストを要素（空白区切り）に分割する。プレビューの縦並び表示にも使う。
     * 数字でも文字でも **1 文字だけの要素は無視**（OCR ノイズ除け）。
     */
    public static List<String> elements(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\s+")) {
            String t = raw.trim();
            if (t.length() <= 1) continue; // 空・1文字は無視
            out.add(t);
        }
        return out;
    }

    /**
     * トークンが符号1文字だけなら、その符号を返す（true=負, false=正）。符号でなければ null。
     * 負: - − △ ▲ / 正: +（全角は事前に半角化済み）。
     */
    private static Boolean signOnly(String s) {
        if (s.length() != 1) return null;
        char c = s.charAt(0);
        if (c == '-' || c == '−' || c == '△' || c == '▲') return Boolean.TRUE;
        if (c == '+') return Boolean.FALSE;
        return null;
    }

    /** amounts の末尾要素の符号を、直後にあった符号1文字で上書きする。 */
    private static void applyTrailingSign(List<String> amounts, boolean negative) {
        if (amounts.isEmpty()) return;
        int i = amounts.size() - 1;
        try {
            long v = Math.abs(Long.parseLong(amounts.get(i)));
            amounts.set(i, String.valueOf(negative ? -v : v));
        } catch (NumberFormatException ignore) {
            // 数値でなければ何もしない
        }
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    // ---- date -------------------------------------------------------------

    private static String matchDate(String s, int fallbackYear) {
        Matcher m = DATE_Y4.matcher(s);
        if (m.matches()) return iso(fullYear(n(m, 1), fallbackYear), n(m, 2), n(m, 3));
        m = DATE_Y2.matcher(s);
        if (m.matches()) return iso(fullYear(n(m, 1), fallbackYear), n(m, 2), n(m, 3));
        m = DATE_JP_Y.matcher(s);
        if (m.matches()) return iso(fullYear(n(m, 1), fallbackYear), n(m, 2), n(m, 3));
        m = DATE_MD.matcher(s);
        if (m.matches()) return iso(fallbackYear, n(m, 1), n(m, 2));
        m = DATE_JP_MD.matcher(s);
        if (m.matches()) return iso(fallbackYear, n(m, 1), n(m, 2));
        return null;
    }

    /** 2桁年を4桁に補完（未来すぎる場合は1900年代とみなす）。4桁はそのまま。 */
    private static int fullYear(int y, int fallbackYear) {
        if (y >= 100) return y;
        int full = 2000 + y;
        if (full > fallbackYear + 1) full -= 100;
        return full;
    }

    private static String iso(int y, int mo, int d) {
        if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
        return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d);
    }

    private static int n(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }

    // ---- amount -----------------------------------------------------------

    private static final class Amount {
        final boolean negative;
        final String digits;

        Amount(boolean negative, String digits) {
            this.negative = negative;
            this.digits = digits;
        }
    }

    /** 金額トークンなら符号と数字を返す。金額でなければ null。 */
    private static Amount parseAmount(String s) {
        String t = s.replace("¥", "").replace("￥", "").replace("円", "").replace(",", "").trim();
        if (t.isEmpty()) return null;

        boolean neg = false;
        if (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
            neg = true;
            t = t.substring(1, t.length() - 1);
        }
        if (!t.isEmpty()) {
            char c0 = t.charAt(0);
            if (c0 == '-' || c0 == '−' || c0 == '△' || c0 == '▲') {
                neg = true;
                t = t.substring(1);
            } else if (c0 == '+') {
                t = t.substring(1);
            }
        }
        t = t.trim();
        if (t.isEmpty() || !DIGITS.matcher(t).matches()) return null;
        return new Amount(neg, t);
    }

    // ---- util -------------------------------------------------------------

    /** 全角数字・記号を半角へ（費目には使わず、判定用のみ）。 */
    private static String toHalfWidth(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                b.append((char) ('0' + (c - '０')));
            } else if (c == '，') {
                b.append(',');
            } else if (c == '．') {
                b.append('.');
            } else if (c == '／') {
                b.append('/');
            } else if (c == '＋') {
                b.append('+');
            } else if (c == '－') {
                b.append('-');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
