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

    private static final Pattern DATE_FULL = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DATE_JP_FULL = Pattern.compile("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日?$");
    private static final Pattern DATE_MD = Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})$");
    private static final Pattern DATE_JP_MD = Pattern.compile("^(\\d{1,2})月(\\d{1,2})日?$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public static List<Record> parse(String text) {
        List<Record> out = new ArrayList<>();
        if (text == null) return out;
        int year = Calendar.getInstance().get(Calendar.YEAR);

        String currentDate = null;
        StringBuilder item = new StringBuilder();

        for (String raw : text.split("\\s+")) {
            String tok = raw.trim();
            if (tok.isEmpty()) continue;
            String half = toHalfWidth(tok);

            String date = matchDate(half, year);
            if (date != null) {
                currentDate = date;       // 以降のレコードへ引き継ぐ
                item.setLength(0);
                continue;
            }
            if (currentDate == null) continue; // 最初の日付より前は無視

            Amount amt = parseAmount(half);
            if (amt != null) {
                if (amt.digits.length() > 7) continue; // 7 桁超はスキップ
                long v = Long.parseLong(amt.digits);
                Record r = new Record();
                r.date = currentDate;
                r.category = "";
                r.item = item.toString().trim();
                r.amount = amt.negative ? -v : v;
                out.add(r);
                item.setLength(0);
                continue;
            }

            if (item.length() > 0) item.append(' ');
            item.append(tok); // 費目は元の表記のまま
        }
        return out;
    }

    // ---- date -------------------------------------------------------------

    private static String matchDate(String s, int fallbackYear) {
        Matcher m = DATE_FULL.matcher(s);
        if (m.matches()) return iso(n(m, 1), n(m, 2), n(m, 3));
        m = DATE_JP_FULL.matcher(s);
        if (m.matches()) return iso(n(m, 1), n(m, 2), n(m, 3));
        m = DATE_MD.matcher(s);
        if (m.matches()) return iso(fallbackYear, n(m, 1), n(m, 2));
        m = DATE_JP_MD.matcher(s);
        if (m.matches()) return iso(fallbackYear, n(m, 1), n(m, 2));
        return null;
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
