package com.micklab.budget.util;

import java.util.Calendar;
import java.util.Locale;

/** "yyyy-MM-dd" 文字列と Calendar の相互変換ヘルパ。 */
public final class DateUtil {

    private DateUtil() {
    }

    public static String iso(int year, int month0, int day) {
        return String.format(Locale.US, "%04d-%02d-%02d", year, month0 + 1, day);
    }

    public static String today() {
        Calendar c = Calendar.getInstance();
        return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    /** "yyyy-MM-dd" を Calendar に。解析できなければ今日。 */
    public static Calendar parse(String iso) {
        Calendar c = Calendar.getInstance();
        if (iso != null && iso.length() == 10) {
            try {
                int y = Integer.parseInt(iso.substring(0, 4));
                int m = Integer.parseInt(iso.substring(5, 7));
                int d = Integer.parseInt(iso.substring(8, 10));
                c.set(y, m - 1, d, 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
            } catch (NumberFormatException ignored) {
            }
        }
        return c;
    }

    public static String firstOfMonth() {
        Calendar c = Calendar.getInstance();
        return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), 1);
    }

    public static String lastOfMonth() {
        Calendar c = Calendar.getInstance();
        int last = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), last);
    }
}
