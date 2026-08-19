package blater.nql.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;

/*
 * Responsibility: Provides local parser helpers for rule text and
 * quoted identifier/string values.
 */
public final class ParseUtils {
    private ParseUtils() {}

    public static String textOf(ParserRuleContext ctx) {
        if (ctx == null || ctx.start == null) return "";
        int stop = ctx.stop != null ? ctx.stop.getStopIndex() : ctx.start.getStartIndex();
        return ctx.start.getInputStream().getText(
                new Interval(ctx.start.getStartIndex(), stop));
    }

    public static String unquoteString(String s) {
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }

    public static String unquoteIdentifier(String s) {
        if (s == null || s.length() < 2) return s;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
            return s.substring(1, s.length() - 1);
        }
        if (first == '[' && last == ']') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
