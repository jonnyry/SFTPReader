package org.endeavourhealth.sftpreader.utilities;

public class StringHelper {
    public static String replaceLast(String string, String from, String to) {
        int lastIndex = string.lastIndexOf(from);

        if (lastIndex < 0)
            return string;

        String tail = string.substring(lastIndex).replaceFirst(from, to);
        return string.substring(0, lastIndex) + tail;
    }
}
