package com.adobexp.log;

import java.util.Arrays;

/**
 * Pure Java re-implementation of the subset of SLF4J's
 * {@code MessageFormatter} that application code actually uses:
 * {@code "{}"} placeholder substitution with the last-arg-is-Throwable
 * detection rule. Kept inside this bundle so the facade has zero
 * runtime dependency on {@code org.slf4j.helpers}, which is one of the
 * packages flagged by AEMaaCS Code Quality rule {@code java:S1874}.
 */
final class MessageFormatter {

    private MessageFormatter() {
        // utility class - not instantiable
    }

    static FormattedMessage format(String pattern, Object arg) {
        return format(pattern, new Object[]{arg});
    }

    static FormattedMessage format(String pattern, Object arg1, Object arg2) {
        return format(pattern, new Object[]{arg1, arg2});
    }

    /**
     * Formats an SLF4J-style pattern. If the last element of
     * {@code args} is a {@link Throwable} and the number of
     * {@code "{}"} placeholders is strictly less than the number of
     * args, the throwable is pulled out and returned separately so the
     * downstream sink can handle it (stack trace etc.) the same way
     * SLF4J does.
     */
    static FormattedMessage format(String pattern, Object[] args) {
        Throwable throwable = null;
        int argCount = args == null ? 0 : args.length;
        if (args != null && argCount > 0 && args[argCount - 1] instanceof Throwable) {
            final int placeholders = countPlaceholders(pattern);
            if (placeholders < argCount) {
                throwable = (Throwable) args[argCount - 1];
                argCount--;
            }
        }
        if (pattern == null) {
            return new FormattedMessage("", throwable);
        }
        if (argCount == 0) {
            return new FormattedMessage(pattern, throwable);
        }
        final int len = pattern.length();
        final StringBuilder sb = new StringBuilder(len + 32);
        int i = 0;
        int ai = 0;
        while (i < len) {
            final int ph = pattern.indexOf("{}", i);
            if (ph < 0 || ai >= argCount) {
                sb.append(pattern, i, len);
                break;
            }
            if (ph > 0 && pattern.charAt(ph - 1) == '\\') {
                sb.append(pattern, i, ph - 1).append("{}");
                i = ph + 2;
            } else {
                sb.append(pattern, i, ph);
                sb.append(toSafeString(args[ai++]));
                i = ph + 2;
            }
        }
        return new FormattedMessage(sb.toString(), throwable);
    }

    private static int countPlaceholders(String pattern) {
        if (pattern == null) {
            return 0;
        }
        int count = 0;
        int i = 0;
        while (true) {
            final int ph = pattern.indexOf("{}", i);
            if (ph < 0) {
                return count;
            }
            if (ph == 0 || pattern.charAt(ph - 1) != '\\') {
                count++;
            }
            i = ph + 2;
        }
    }

    private static String toSafeString(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg.getClass().isArray()) {
            if (arg instanceof Object[]) {
                return Arrays.deepToString((Object[]) arg);
            }
            if (arg instanceof boolean[]) return Arrays.toString((boolean[]) arg);
            if (arg instanceof byte[])    return Arrays.toString((byte[]) arg);
            if (arg instanceof char[])    return Arrays.toString((char[]) arg);
            if (arg instanceof double[])  return Arrays.toString((double[]) arg);
            if (arg instanceof float[])   return Arrays.toString((float[]) arg);
            if (arg instanceof int[])     return Arrays.toString((int[]) arg);
            if (arg instanceof long[])    return Arrays.toString((long[]) arg);
            if (arg instanceof short[])   return Arrays.toString((short[]) arg);
        }
        try {
            return String.valueOf(arg);
        } catch (Throwable t) {
            return "[FAILED toString(): " + t + "]";
        }
    }

    /** Result of a format operation: rendered message plus the detached throwable, if any. */
    static final class FormattedMessage {
        final String message;
        final Throwable throwable;

        FormattedMessage(String message, Throwable throwable) {
            this.message = message;
            this.throwable = throwable;
        }
    }
}
