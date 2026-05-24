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
import java.util.LinkedHashMap;
import java.util.Map;

public class AivisSpeechEngine implements VoiceEngine {
    private static final Gson GSON = new Gson();
    private final BotConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AivisSpeechEngine(BotConfig config) {
        this.config = config;
    }

    @Override
    public byte[] synthesize(String text, Database.VoiceSettings settings) throws IOException, InterruptedException {
        double pitch = (settings.pitch() - 100) * 0.001;
        double speed = settings.speed();

        if (config.aivisspeech.edition != null && config.aivisspeech.edition.engine) {
            return synthesizeWithEngine(text, settings.voiceName(), pitch, speed);
        }
        if (config.aivisspeech.edition != null && config.aivisspeech.edition.cloud) {
            return synthesizeWithCloud(text, settings.voiceName(), pitch, speed);
        }
        throw new IOException("AivisSpeechのエンジンが有効になっていません");
    }

    private byte[] synthesizeWithEngine(String text, String speaker, double pitch, double speed)
            throws IOException, InterruptedException {
        String baseUrl = config.aivisspeech.url.replaceAll("/$", "");
        String queryUrl = baseUrl + "/audio_query?text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&speaker=" + URLEncoder.encode(speaker, StandardCharsets.UTF_8);

        HttpRequest queryRequest = HttpRequest.newBuilder(URI.create(queryUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> queryResponse = client.send(queryRequest, HttpResponse.BodyHandlers.ofString());
        if (queryResponse.statusCode() != 200) {
            throw new IOException("audio_queryのリクエストに失敗しました");
        }

        JsonObject query = GSON.fromJson(queryResponse.body(), JsonObject.class);
        query.addProperty("pitchScale", pitch);
        query.addProperty("speedScale", speed);

        String synthesisUrl = baseUrl + "/synthesis?speaker=" + URLEncoder.encode(speaker, StandardCharsets.UTF_8);
        HttpRequest synthesisRequest = HttpRequest.newBuilder(URI.create(synthesisUrl))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(query)))
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> synthesisResponse = client.send(synthesisRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (synthesisResponse.statusCode() != 200) {
            throw new IOException("synthesisのリクエストに失敗しました");
        }
        return synthesisResponse.body();
    }

    private byte[] synthesizeWithCloud(String text, String modelUuid, double pitch, double speed)
            throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_uuid", modelUuid);
        payload.put("text", text);
        payload.put("pitch", pitch);
        payload.put("speaking_rate", speed);
        payload.put("output_format", "opus");
        payload.put("output_sampling_rate", 48000);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.aivis-project.com/v1/tts/synthesize"))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .header("Authorization", "Bearer " + config.aivisspeech.apikey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return switch (response.statusCode()) {
            case 200 -> response.body();
            case 401 -> throw new IOException("APIキーが正しくありません");
            case 402 -> throw new IOException("クレジット残高が不足しています");
            case 404 -> throw new IOException("指定されたモデル UUID の音声合成モデルが見つかりません");
            case 429 -> throw new IOException("APIレート制限に達しました");
            case 500 -> throw new IOException("サーバーの接続中に不明なエラーが発生しました");
            case 503 -> throw new IOException("サーバーの接続に失敗しました");
            case 504 -> throw new IOException("サーバーの接続にタイムアウトしました");
            default -> throw new IOException("予期しないエラーが発生しました: HTTP " + response.statusCode());
        };
    }
}
