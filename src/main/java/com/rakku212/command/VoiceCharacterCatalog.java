package com.rakku212.command;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rakku212.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class VoiceCharacterCatalog {
    private static final Logger log = LoggerFactory.getLogger(VoiceCharacterCatalog.class);
    private static final Gson GSON = new Gson();

    public static final Map<String, String> ENGINE_KEY = Map.of(
            "aquestalk1", "AquesTalk1",
            "aquestalk2", "AquesTalk2",
            "aquestalk10", "AquesTalk10",
            "voicevox", "voicevox",
            "aivisspeech", "aivisspeech"
    );

    private final Map<String, List<VoiceEntry>> voicesByEngine;

    public VoiceCharacterCatalog() {
        this.voicesByEngine = load();
    }

    public record VoiceEntry(String name, String value) {
    }

    public Optional<String> validate(String engine, String voice, BotConfig config) {
        if (!Boolean.TRUE.equals(config.engine_enabled.get(engine))) {
            return Optional.of(engine + "は無効になっています。");
        }
        if (!ENGINE_KEY.containsKey(engine)) {
            return Optional.of("無効なエンジンが指定されました。");
        }
        String engineName = ENGINE_KEY.get(engine);
        List<VoiceEntry> voices = voicesByEngine.get(engineName);
        if (voices == null || voices.isEmpty()) {
            return Optional.of(engine + "の音声キャラクターが見つかりません。");
        }
        boolean valid = voices.stream().anyMatch(v -> v.value().equals(voice));
        if (!valid) {
            return Optional.of("無効な" + engine + "の音声が指定されました。");
        }
        return Optional.empty();
    }

    public String getVoiceName(String engine, String voice) {
        String engineName = ENGINE_KEY.get(engine);
        if (engineName == null) {
            return "";
        }
        List<VoiceEntry> voices = voicesByEngine.get(engineName);
        if (voices == null) {
            return "";
        }
        return voices.stream()
                .filter(v -> v.value().equals(voice))
                .map(VoiceEntry::name)
                .findFirst()
                .orElse("");
    }

    public List<VoiceEntry> getVoices(String engine) {
        String engineName = ENGINE_KEY.get(engine);
        if (engineName == null) {
            return List.of();
        }
        return voicesByEngine.getOrDefault(engineName, List.of());
    }

    private static Map<String, List<VoiceEntry>> load() {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(
                        VoiceCharacterCatalog.class.getResourceAsStream("/voice_character.json"),
                        "voice_character.json"
                ),
                StandardCharsets.UTF_8
        )) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            Map<String, List<VoiceEntry>> result = new HashMap<>();
            for (String key : root.keySet()) {
                JsonArray array = root.getAsJsonArray(key);
                List<VoiceEntry> entries = new ArrayList<>();
                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    entries.add(new VoiceEntry(
                            obj.get("name").getAsString(),
                            obj.get("value").getAsString()
                    ));
                }
                result.put(key, List.copyOf(entries));
            }
            return result;
        } catch (Exception e) {
            log.error("音声キャラクターの設定を読み込めませんでした", e);
            return Collections.emptyMap();
        }
    }
}
