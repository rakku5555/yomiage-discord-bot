package com.rakku212.voice.engine;

import com.rakku212.config.BotConfig;

public final class VoiceEngineFactory {
    private final BotConfig config;
    private final VoiceVoxEngine voiceVoxEngine;
    private final AquesTalkEngine aquesTalkEngine;
    private final AivisSpeechEngine aivisSpeechEngine;

    public VoiceEngineFactory(BotConfig config) {
        this.config = config;
        this.voiceVoxEngine = new VoiceVoxEngine(config);
        this.aquesTalkEngine = new AquesTalkEngine(config);
        this.aivisSpeechEngine = new AivisSpeechEngine(config);
    }

    public VoiceEngine getEngine(String engineName) {
        if (engineName.contains("aquestalk")) {
            return aquesTalkEngine;
        }
        return switch (engineName) {
            case "voicevox" -> voiceVoxEngine;
            case "aivisspeech" -> aivisSpeechEngine;
            default -> throw new IllegalArgumentException("無効なエンジン: " + engineName);
        };
    }

    public boolean isEngineEnabled(String engineName) {
        return Boolean.TRUE.equals(config.engine_enabled.get(engineName));
    }
}
