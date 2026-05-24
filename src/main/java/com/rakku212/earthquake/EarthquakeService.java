package com.rakku212.earthquake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rakku212.voice.VoiceChannelService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class EarthquakeService implements WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(EarthquakeService.class);
    private static final URI WEBSOCKET_URI = URI.create("wss://api.p2pquake.net/v2/ws");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final VoiceChannelService voiceChannelService;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private JDA jda;
    private WebSocket webSocket;

    public EarthquakeService(VoiceChannelService voiceChannelService) {
        this.voiceChannelService = voiceChannelService;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public void start() {
        connect();
    }

    public void shutdown() {
        scheduler.shutdownNow();
        closeWebSocket();
    }

    private void connect() {
        closeWebSocket();
        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(WEBSOCKET_URI, this)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.error("WebSocket接続エラー: {}", error.getMessage());
                        scheduleReconnect();
                    } else {
                        this.webSocket = socket;
                        log.info("P2P地震情報のWebSocket接続が確立されました");
                    }
                });
    }

    private void closeWebSocket() {
        WebSocket current = this.webSocket;
        if (current == null) {
            return;
        }
        this.webSocket = null;
        current.abort();
    }

    private void scheduleReconnect() {
        log.info("5秒後に再接続を試行します...");
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(Long.MAX_VALUE);
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonObject root = JsonParser.parseString(data.toString()).getAsJsonObject();
            if (root.get("code").getAsInt() != 551) {
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
            JsonObject earthquake = root.getAsJsonObject("earthquake");
            int maxScale = earthquake.get("maxScale").getAsInt();
            if (maxScale < 45) {
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
            broadcastEarthquake(earthquake);
        } catch (Exception e) {
            log.error("地震情報の処理エラー", e);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (this.webSocket == webSocket) {
            this.webSocket = null;
        }
        log.info("WebSocket接続が閉じられました");
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.error("WebSocketエラー: {}", error.getMessage());
        scheduleReconnect();
    }

    private void broadcastEarthquake(JsonObject earthquake) {
        if (jda == null) {
            return;
        }
        String message = buildMessage(earthquake);
        for (Guild guild : jda.getGuilds()) {
            if (!guild.getAudioManager().isConnected()) {
                continue;
            }
            try {
                voiceChannelService.readMessage(message, guild, null);
                log.info("{}のボイスチャンネルに地震情報をブロードキャストしました", guild.getName());
            } catch (Exception e) {
                log.error("{}での地震情報ブロードキャストに失敗しました", guild.getName(), e);
            }
        }
    }

    private static String buildMessage(JsonObject earthquake) {
        JsonObject hypocenter = earthquake.getAsJsonObject("hypocenter");
        String name = hypocenter.get("name").getAsString();
        double magnitude = hypocenter.get("magnitude").getAsDouble();
        int depth = hypocenter.get("depth").getAsInt();
        int maxScale = earthquake.get("maxScale").getAsInt();

        String magnitudeText = magnitude == -1 ? "マグニチュードは不明" : "マグニチュードは" + magnitude;
        String depthText = switch (depth) {
            case -1 -> "深さは不明";
            case 0 -> "深さはごく浅い";
            default -> "深さは" + depth + "キロメートル";
        };

        LocalDateTime time = LocalDateTime.parse(earthquake.get("time").getAsString(), TIME_FORMAT);
        String formattedTime = time.getYear() + "年" + time.getMonthValue() + "月" + time.getDayOfMonth() + "日 "
                + time.getHour() + "時" + time.getMinute() + "分";

        return "地震情報です。" + formattedTime + "に、" + name + "で"
                + convertMaxScaleToIntensity(maxScale) + "の地震が発生しました。"
                + magnitudeText + "、" + depthText + "です。";
    }

    private static String convertMaxScaleToIntensity(int maxScale) {
        return switch (maxScale) {
            case -1 -> "震度情報なし";
            case 10 -> "震度1";
            case 20 -> "震度2";
            case 30 -> "震度3";
            case 40 -> "震度4";
            case 45 -> "震度5弱";
            case 50 -> "震度5強";
            case 55 -> "震度6弱";
            case 60 -> "震度6強";
            case 70 -> "震度7";
            default -> "不明な震度(" + maxScale + ")";
        };
    }
}
