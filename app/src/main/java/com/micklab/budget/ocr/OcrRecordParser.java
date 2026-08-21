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

    public static List<Record> parse(String text) {
        List<Record> out = new ArrayList<>();
        if (text == null) return out;
        int year = Calendar.getInstance().get(Calendar.YEAR);

        String currentDate = null;   // 直近の日付（以降へ引き継ぐ）
        String firstDate = null;     // 最初に現れた日付（日付前レコードの補完に使う）
        StringBuilder item = new StringBuilder();
        boolean skipNextNumber = false;

        for (String raw : text.split("\\s+")) {
            String tok = raw.trim();
            if (tok.isEmpty()) continue;
            String half = toHalfWidth(tok);

            // 「残高」を含む要素は無視。数字が付いていなければ直後に来る数字も無視する。
            if (tok.contains("残高")) {
                skipNextNumber = !hasDigit(half);
                continue;
            }
            if (skipNextNumber) {
                skipNextNumber = false;
                if (parseAmount(half) != null) continue; // 残高の値を無視
                // 数字でなければ通常処理へフォールスルー
            }

            String date = matchDate(half, year);
            if (date != null) {
                currentDate = date;
                if (firstDate == null) {
                    firstDate = date;
                    for (Record r : out) {          // 日付前に積んだレコードを最初の日付で補完
                        if (r.date == null) r.date = date;
                    }
                }
                continue;
            }

            Amount amt = parseAmount(half);
            if (amt != null) {
                if (amt.digits.length() > 7) continue; // 7 桁超はスキップ
                long v = Long.parseLong(amt.digits);
                Record r = new Record();
                r.date = currentDate;                // 日付未確定なら null（後で補完）
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

        // 日付が最後まで現れなかったレコード（全体に日付が無い）は除外する。
        List<Record> result = new ArrayList<>();
        for (Record r : out) {
            if (r.date != null) result.add(r);
        }
        return result;
    }

    /** OCR テキストを要素（空白区切り）に分割する。プレビューの縦並び表示にも使う。 */
    public static List<String> elements(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\s+")) {
            String t = raw.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
