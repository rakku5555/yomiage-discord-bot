package com.rakku212.config;

import java.util.Map;

public class BotConfig {
    public boolean debug;
    public DiscordConfig discord;
    public DatabaseConfig database;
    public RedisConfig redis;
    public Map<String, Boolean> engine_enabled;
    public VoiceVoxConfig voicevox;
    public AivisSpeechConfig aivisspeech;
    public AquestalkConfig aquestalk;

    public static class DiscordConfig {
        public String token;
        public int max_length;
    }

    public static class DatabaseConfig {
        public String path;
    }

    public static class RedisConfig {
        public String host;
        public int port;
        public int db;
        public String passwd;
    }

    public static class VoiceVoxConfig {
        public String url;
    }

    public static class AivisSpeechConfig {
        public String url;
        public String apikey;
        public AivisEdition edition;

        public static class AivisEdition {
            public boolean engine;
            public boolean cloud;
        }
    }

    public static class AquestalkConfig {
        public String url;
    }
}
