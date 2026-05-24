package com.rakku212.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ConsoleEncoding {

    public static final String CHARSET_PROPERTY = "console.charset";
    private static final Charset WINDOWS_CONSOLE = Charset.forName("Windows-31J");

    public static void configure() {
        if (System.getProperty(CHARSET_PROPERTY) != null) {
            return;
        }
        if (isWindows()) {
            System.setProperty(CHARSET_PROPERTY, WINDOWS_CONSOLE.name());
        } else {
            System.setProperty(CHARSET_PROPERTY, StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
