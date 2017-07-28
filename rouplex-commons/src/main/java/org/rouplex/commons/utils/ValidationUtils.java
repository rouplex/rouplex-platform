package org.rouplex.commons.utils;


import java.text.ParseException;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ValidationUtils {
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
