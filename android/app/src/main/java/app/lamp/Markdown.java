package app.lamp;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

/**
 * Небольшой разбор ответа модели: жирный, списки, код и нажимаемые ссылки.
 * Полный Markdown не нужен, модель почти всегда пишет именно это.
 */
public final class Markdown {
    private Markdown() {
    }

    public static CharSequence format(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("```", index)) {
                int close = text.indexOf("```", index + 3);
                if (close < 0) {
                    close = text.length();
                }
                int content = text.indexOf('\n', index + 3);
                if (content < 0 || content > close) {
                    content = index + 3;
                } else {
                    content++;
                }
                appendBreak(out);
                int start = out.length();
                out.append(text.substring(content, close).trim());
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0xFF242424), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                appendBreak(out);
                index = Math.min(text.length(), close + 3);
                continue;
            }
            int end = text.indexOf('\n', index);
            if (end < 0) {
                end = text.length();
            }
            appendLine(out, text.substring(index, end));
            if (end < text.length()) {
                out.append('\n');
            }
            index = end + 1;
        }
        return out;
    }

    private static void appendBreak(SpannableStringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void appendLine(SpannableStringBuilder out, String line) {
        String trimmed = stripStart(line);
        if (trimmed.startsWith("- ") || (trimmed.startsWith("* ") && !trimmed.startsWith("**"))) {
            out.append("• ");
            appendInline(out, trimmed.substring(2));
            return;
        }
        int number = listNumber(trimmed);
        if (number > 0) {
            out.append(trimmed, 0, number);
            appendInline(out, trimmed.substring(number));
            return;
        }
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes > 0 && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ') {
            int start = out.length();
            appendInline(out, trimmed.substring(hashes + 1));
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        appendInline(out, line);
    }

    private static String stripStart(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return line.substring(index);
    }

    /** Длина префикса «1. » или «2) », иначе 0. */
    private static int listNumber(String line) {
        int index = 0;
        while (index < line.length() && Character.isDigit(line.charAt(index))) {
            index++;
        }
        if (index == 0 || index >= line.length()) {
            return 0;
        }
        char mark = line.charAt(index);
        if ((mark == '.' || mark == ')') && index + 1 < line.length() && line.charAt(index + 1) == ' ') {
            return index + 2;
        }
        return 0;
    }

    private static void appendInline(SpannableStringBuilder out, String line) {
        int index = 0;
        while (index < line.length()) {
            if (line.startsWith("**", index) || line.startsWith("__", index)) {
                String mark = line.substring(index, index + 2);
                int close = line.indexOf(mark, index + 2);
                if (close > index + 2) {
                    int start = out.length();
                    appendPlain(out, line.substring(index + 2, close));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    index = close + 2;
                    continue;
                }
            }
            if (line.charAt(index) == '`') {
                int close = line.indexOf('`', index + 1);
                if (close > index) {
                    int start = out.length();
                    out.append(line, index + 1, close);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(0xFF242424), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    index = close + 1;
                    continue;
                }
            }
            if (line.charAt(index) == '[') {
                int labelEnd = line.indexOf(']', index + 1);
                if (labelEnd > index && labelEnd + 1 < line.length() && line.charAt(labelEnd + 1) == '(') {
                    int urlEnd = line.indexOf(')', labelEnd + 2);
                    if (urlEnd > labelEnd) {
                        String label = line.substring(index + 1, labelEnd);
                        String url = line.substring(labelEnd + 2, urlEnd);
                        int start = out.length();
                        out.append(label.isEmpty() ? url : label);
                        out.setSpan(new URLSpan(url), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        index = urlEnd + 1;
                        continue;
                    }
                }
            }
            if (line.startsWith("https://", index) || line.startsWith("http://", index)) {
                int end = index;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                while (end > index && ".,);".indexOf(line.charAt(end - 1)) >= 0) {
                    end--;
                }
                int start = out.length();
                out.append(line, index, end);
                out.setSpan(new URLSpan(line.substring(index, end)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = end;
                continue;
            }
            out.append(line.charAt(index));
            index++;
        }
    }

    /** Внутри жирного тоже могут быть ссылки. */
    private static void appendPlain(SpannableStringBuilder out, String line) {
        appendInline(out, line);
    }
}
