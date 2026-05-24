package com.rakku212.voice.engine;

import com.google.gson.Gson;
import com.rakku212.config.BotConfig;
import com.rakku212.database.Database;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class AquesTalkEngine implements VoiceEngine {
    private static final Gson GSON = new Gson();
    private final BotConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
        payload.put("speed", normalizeSpeed(settings.speed()));
        payload.put("pitch", settings.pitch());
        payload.put("accent", settings.accent());
        payload.put("lmd", settings.lmd());

        String url = config.aquestalk.url.replaceAll("/$", "") + "/synthesis";
        byte[] jsonBody = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "audio/wav")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String body = response.body() != null && response.body().length > 0
                    ? new String(response.body(), StandardCharsets.UTF_8)
                    : "";
            throw new IOException("AquesTalk synthesisのリクエストに失敗しました: HTTP "
                    + response.statusCode() + (body.isEmpty() ? "" : " " + body));
        }
        return response.body();
    }

    private static int normalizeSpeed(double speed) {
        if (speed == 1.0) {
            return 100;
        }
        int value = (int) Math.round(speed);
        if (value < 50 || value > 200) {
            return 100;
        }
        return value;
    }
}
