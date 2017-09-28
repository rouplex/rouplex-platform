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

    public static void checkIpAddress(String ipAddress, String fieldName) {
        if (ipAddress == null) {
            throw new IllegalArgumentException(String.format("Argument %s cannot be null", fieldName));
        }

        if (!IP_ADDRESS_PATTERN.matcher(ipAddress).matches()) {
            throw new IllegalArgumentException(String.format("Argument %s is not an ip address", fieldName));
        }
    }

    public static void checkNonNullArg(Object val, String fieldName) {
        if (val == null) {
            throw new IllegalArgumentException(String.format("Argument %s cannot be null", fieldName));
        }
    }

    public static void checkNonNegativeArg(int val, String fieldName) {
        if (val < 0) {
            throw new IllegalArgumentException(String.format("Argument %s cannot be negative", fieldName));
        }
    }

    public static void checkPositiveArg(int val, String fieldName) {
        if (val <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument %s cannot be less than 1", fieldName));
        }
    }

    public static void checkPositiveArgDiff(int val, String fieldName1, String fieldName2) {
        if (val <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument %s cannot be greater or equal than argument %s", fieldName1, fieldName2));
        }
    }

    public static void checkNonNegativeArg(long val, String fieldName) {
        if (val < 0) {
            throw new IllegalArgumentException(String.format("Argument %s cannot be negative", fieldName));
        }
    }

    public static void checkPositiveArg(long val, String fieldName) {
        if (val <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument %s cannot be less than 1", fieldName));
        }
    }

    public static void checkPositiveArgDiff(long val, String fieldName1, String fieldName2) {
        if (val <= 0) {
            throw new IllegalArgumentException(String.format(
                "Argument %s cannot be greater or equal than argument %s", fieldName1, fieldName2));
        }
    }

    public static void checkDateTimeString(String dateTime, String fieldName) {
        try {
            TimeUtils.convertIsoInstantToMillis(dateTime);
        } catch (ParseException pe) {
            throw new IllegalArgumentException(String.format("Argument %s cannot be parsed as a dateTime ", fieldName));
        }
    }
}
