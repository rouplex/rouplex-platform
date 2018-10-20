package org.rouplex.commons.logging;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RxSimpleFormatter extends Formatter {
    private final static String NEW_LINE = System.getProperty("line.separator");
    private final static SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS");

    @Override
    public String format(LogRecord record) {
        String className = record.getSourceClassName();
        className = className.substring(className.lastIndexOf('.') + 1);

        return String.format("%s %s [%s] %s %s%s",
                FORMATTER.format(new Date(record.getMillis())), record.getLevel(),
                Thread.currentThread().getName(), className, record.getMessage(), NEW_LINE);
    }
}
