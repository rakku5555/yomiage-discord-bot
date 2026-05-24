package com.rakku212.console;

import com.rakku212.database.Database;
import com.rakku212.voice.VoiceChannelService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class ConsoleListener {
    private static final Logger log = LoggerFactory.getLogger(ConsoleListener.class);

    public static void start(JDA jda, Database database, VoiceChannelService voiceChannelService) {
        Thread thread = new Thread(() -> {
            try (var scanner = new java.util.Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    String command = scanner.nextLine().trim();
                    if ("stop".equals(command)) {
                        shutdown(jda, database, voiceChannelService);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("コンソール入力が終了しました");
            }
        }, "console");
        thread.setDaemon(true);
        thread.start();
    }

    private static void shutdown(JDA jda, Database database, VoiceChannelService voiceChannelService) {
        voiceChannelService.shutdown();
        database.close();
        jda.shutdown();
        try {
            if (!jda.awaitShutdown(Duration.ofSeconds(10))) {
                jda.shutdownNow();
                jda.awaitShutdown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jda.shutdownNow();
        }
        log.info("Discordクライアントを閉じました");
    }
}
