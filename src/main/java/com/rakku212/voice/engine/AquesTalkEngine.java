package com.rakku212.voice.engine;

import com.google.gson.Gson;
import com.rakku212.config.BotConfig;
import com.rakku212.database.Database;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class AquesTalkEngine implements VoiceEngine {
    private static final Gson GSON = new Gson();
    private final BotConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AquesTalkEngine(BotConfig config) {
        this.config = config;
    }

    @Override
    public byte[] synthesize(String text, Database.VoiceSettings settings) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("engine", settings.engine());
        payload.put("voice", settings.voiceName());
        payload.put("speed", settings.engine().startsWith("aquestalk") && settings.speed() <= 5
                ? (int) settings.speed()
                : (int) settings.speed());
        payload.put("pitch", settings.pitch());
        payload.put("accent", settings.accent());
        payload.put("lmd", settings.lmd());

        String url = config.aquestalk.url.replaceAll("/$", "") + "/synthesis";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("AquesTalk synthesisのリクエストに失敗しました");
        }
        return response.body();
    }
}
