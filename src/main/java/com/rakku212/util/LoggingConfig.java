package com.rakku212.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

public final class LoggingConfig {

    public static void configure(boolean debug) {
        if (!debug) {
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger("com.rakku212").setLevel(Level.DEBUG);
    }
}
