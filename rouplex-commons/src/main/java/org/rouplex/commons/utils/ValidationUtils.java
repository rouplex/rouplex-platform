package org.rouplex.commons.utils;

import java.text.ParseException;
import java.util.regex.Pattern;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ValidationUtils {
    private static Pattern IP_ADDRESS_PATTERN = Pattern.compile(
        "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");

    public static String checkedIpAddress(String argValue, String argName) {
        if (argValue == null) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be null", argName));
        }

        if (!IP_ADDRESS_PATTERN.matcher(argValue).matches()) {
            throw new IllegalArgumentException(String.format("Argument [%s] is not an ip address", argName));
        }

        return argValue;
    }

    public static <T> T checkedNotNull(T argValue, String argName) {
        if (argValue == null) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be null", argName));
        }

        return argValue;
    }

    public static int checkedNotNegative(int argValue, String argName) {
        if (argValue < 0) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be negative", argName));
        }
        return argValue;
    }

    public static int checkedPositive(int argValue, String argName) {
        if (argValue <= 0) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be less than 1", argName));
        }

        return argValue;
    }

    public static int checkedPositiveDiff(int argValue, String argName1, String argName2) {
        if (argValue <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument [%s] cannot be greater or equal than argument [%s]", argName1, argName2));
        }

        return argValue;
    }

    public static long checkedNotNegative(long argValue, String argName) {
        if (argValue < 0) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be negative", argName));
        }

        return argValue;
    }

    public static long checkedPositive(long argValue, String argName) {
        if (argValue <= 0) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be less than 1", argName));
        }

        return argValue;
    }

    public static long checkedGreaterThanMinusOne(long argValue, String argName) {
        if (argValue < -1) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be less than -1", argName));
        }

        return argValue;
    }

    public static long checkedPositiveDiff(long argValue, String argName1, String argName2) {
        if (argValue <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument [%s] cannot be greater or equal than argument %s", argName1, argName2));
        }

        return argValue;
    }

    public static String checkedDateTime(String argValue, String argName) {
        try {
            TimeUtils.convertIsoInstantToMillis(argValue);
            return argValue;
        } catch (ParseException pe) {
            throw new IllegalArgumentException(String.format("Argument [%s] cannot be parsed as a dateTime", argName));
        }
    }
}
