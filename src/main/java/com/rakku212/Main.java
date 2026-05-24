package com.rakku212;

import com.rakku212.command.CommandManager;
import com.rakku212.config.BotConfig;
import com.rakku212.config.Config;
import com.rakku212.console.ConsoleListener;
import com.rakku212.util.ConsoleEncoding;
import com.rakku212.util.LoggingConfig;
import com.rakku212.database.Database;
import com.rakku212.earthquake.EarthquakeService;
import com.rakku212.listener.BotListener;
import com.rakku212.voice.SoundCoreUtil;
import com.rakku212.voice.VoiceChannelService;
import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    public static void main(String[] args) throws Exception {
        ConsoleEncoding.configure();
        Logger log = LoggerFactory.getLogger(Main.class);
        BotConfig config = Config.load();
        LoggingConfig.configure(config.debug);
        if (config.discord.token == null || config.discord.token.isBlank()) {
            log.error("config.yaml の discord.token が設定されていません");
            System.exit(1);
        }

        Database database = new Database();
        database.connect();
        if (config.debug) {
            log.debug("データベース接続完了");
        }
        log.info("sound-core: {}", SoundCoreUtil.version());

        VoiceChannelService voiceChannelService = new VoiceChannelService(database);
        BotListener botListener = new BotListener(config, database, voiceChannelService);
        CommandManager commandManager = new CommandManager(database, voiceChannelService);
        EarthquakeService earthquakeService = new EarthquakeService(voiceChannelService);

        DaveFactory daveFactory = new NativeDaveFactory();
        JDA jda = JDABuilder.createDefault(config.discord.token)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new LDJDADaveSessionFactory(daveFactory)))
                .enableIntents(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.MESSAGE_CONTENT
                )
                .addEventListeners(botListener, commandManager)
                .build();

        jda.awaitReady();
        earthquakeService.setJda(jda);
        commandManager.registerCommands(jda);
        earthquakeService.start();
        ConsoleListener.start(jda, database, voiceChannelService);

        log.info("{} としてログインしました", jda.getSelfUser().getName());
        jda.awaitShutdown();
    }
}
