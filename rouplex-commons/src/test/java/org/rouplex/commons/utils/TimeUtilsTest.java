package org.rouplex.commons.utils;

import org.junit.Test;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TimeUtilsTest {

    @Test
    public void testConvertIsoInstantToMillis() throws Exception {
//        String zonedDateTime = ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//        long epochMilli = ZonedDateTime.parse(zonedDateTime).toInstant().toEpochMilli();
//
//        String rouplexDateTime = TimeUtils.convertMillisToIsoInstant(epochMilli, 0); // 2017-07-23T11:13:38.197Z
//        Assert.assertTrue(rouplexDateTime.equals(zonedDateTime));
    }

    @Test
    public void testConvertIsoInstantToMillis1() throws Exception {
//        String zonedDateTime = ZonedDateTime.now(ZoneId.of("UTC-7")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//        long epochMilli = ZonedDateTime.parse(zonedDateTime).toInstant().toEpochMilli();
//
//        String rouplexDateTime = TimeUtils.convertMillisToIsoInstant(epochMilli, 420); // 2017-07-23T10:15:31.831-01:00
//        Assert.assertTrue(rouplexDateTime.equals(zonedDateTime));
    }
}