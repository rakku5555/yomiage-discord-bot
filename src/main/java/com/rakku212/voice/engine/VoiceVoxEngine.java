package com.rakku212.voice.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rakku212.config.BotConfig;
import com.rakku212.database.Database;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class VoiceVoxEngine implements VoiceEngine {
    private static final Gson GSON = new Gson();
    private final BotConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public VoiceVoxEngine(BotConfig config) {
        this.config = config;
    }

    @Override
    public byte[] synthesize(String text, Database.VoiceSettings settings) throws IOException, InterruptedException {
        int speaker = Integer.parseInt(settings.voiceName());
        double pitch = (settings.pitch() - 100) * 0.001;
        double speed = settings.speed();

        String baseUrl = config.voicevox.url.replaceAll("/$", "");
        String queryUrl = baseUrl + "/audio_query?text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&speaker=" + speaker;

        HttpRequest queryRequest = HttpRequest.newBuilder(URI.create(queryUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());
        if (queryResponse.statusCode() != 200) {
            throw new IOException("audio_queryのリクエストに失敗しました: HTTP " + queryResponse.statusCode());
        }

        JsonObject query = GSON.fromJson(queryResponse.body(), JsonObject.class);
        query.addProperty("pitchScale", pitch);
        query.addProperty("speedScale", speed);

        String synthesisUrl = baseUrl + "/synthesis?speaker=" + speaker;
        HttpRequest synthesisRequest = HttpRequest.newBuilder(URI.create(synthesisUrl))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(query)))
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> synthesisResponse = client.send(synthesisRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (synthesisResponse.statusCode() != 200) {
            throw new IOException("synthesisのリクエストに失敗しました: HTTP " + synthesisResponse.statusCode());
        }
        return synthesisResponse.body();
    }
}
