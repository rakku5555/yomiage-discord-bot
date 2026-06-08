package com.rakku212.voice;

import com.rakku212.config.BotConfig;
import com.rakku212.config.Config;
import com.rakku212.database.Database;
import com.rakku212.voice.engine.VoiceEngine;
import com.rakku212.voice.engine.VoiceEngineFactory;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceChannelService {
    private static final Logger log = LoggerFactory.getLogger(VoiceChannelService.class);
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://(?:[-\\w.]|(?:%[\\da-fA-F]{2}))+(?:/[^\\s]*)?"
    );
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F300}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\u2600-\\u26FF\\u2700-\\u27BF]",
            Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern CUSTOM_EMOJI_MENTION = Pattern.compile("<a?:[a-zA-Z0-9_]+:\\d+>");
    private static final Pattern CUSTOM_EMOJI_SHORTCODE = Pattern.compile(":[a-zA-Z0-9_]+:");

    private final BotConfig config;
    private final Database database;
    private final VoiceEngineFactory engineFactory;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, BlockingQueue<QueuedMessage>> messageQueues = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> readingTasks = new ConcurrentHashMap<>();
    private final Map<Long, Database.VoiceSettings> currentVoiceSettings = new ConcurrentHashMap<>();
    private final Map<Long, Long> skipVersion = new ConcurrentHashMap<>();
    private final Map<Long, PcmAudioPlayer> activePlayers = new ConcurrentHashMap<>();

    public VoiceChannelService(Database database) {
        this.config = Config.load();
        this.database = database;
        this.engineFactory = new VoiceEngineFactory(config);
    }

    public void updateVoiceSettings(long guildId, long userId, Database.VoiceSettings settings) {
        currentVoiceSettings.put(guildKey(guildId, userId), settings);
    }

    public void clearQueue(long guildId) {
        clearQueue(guildId, null);
    }

    public void clearQueue(long guildId, @Nullable AudioManager audioManager) {
        skipVersion.merge(guildId, 1L, Long::sum);

        PcmAudioPlayer player = activePlayers.remove(guildId);
        if (player != null) {
            player.stop();
        }
        if (audioManager != null) {
            audioManager.setSendingHandler(null);
        }

        BlockingQueue<QueuedMessage> queue = messageQueues.get(guildId);
        if (queue != null) {
            queue.clear();
            queue.offer(QueuedMessage.poison());
        }
        readingTasks.remove(guildId);
    }

    public void readMessage(Message message) {
        if (message.getAuthor().isBot() || message.getGuild() == null) {
            return;
        }
        Map<Long, Database.ReadChannel> channels = database.getReadChannels();
        Database.ReadChannel readChannel = channels.get(message.getGuild().getIdLong());
        if (readChannel == null || message.getChannel().getIdLong() != readChannel.chatChannelId()) {
            return;
        }
        readMessage(message.getContentDisplay().replace("\n", " "), message.getGuild(), message.getMember());
    }

    public void readMessage(String text, Guild guild, Member author) {
        executor.submit(() -> {
            try {
                processReadMessage(text, guild, author);
            } catch (Exception e) {
                log.error("読み上げ処理エラー", e);
            }
        });
    }

    private void processReadMessage(String text, Guild guild, Member author) throws Exception {
        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            return;
        }

        text = text.toLowerCase(Locale.ROOT);
        text = applyReplacements(text, database.getDictionaryReplacements(guild.getIdLong()));
        text = applyReplacements(text, database.getGlobalDictionaryReplacements());

        Database.VoiceSettings voiceSettings = null;
        if (author != null) {
            long key = guildKey(guild.getIdLong(), author.getIdLong());
            voiceSettings = currentVoiceSettings.get(key);
            if (voiceSettings == null) {
                voiceSettings = database.getVoiceSettings(guild.getIdLong(), author.getIdLong()).orElse(null);
                if (voiceSettings != null) {
                    currentVoiceSettings.put(key, voiceSettings);
                }
            }
        }

        String engine = "voicevox";
        String voiceName = "2";
        int pitch = 100;
        double speed = 1.0;
        int accent = 100;
        int lmd = 100;
        if (voiceSettings != null) {
            engine = voiceSettings.engine();
            voiceName = voiceSettings.voiceName();
            pitch = voiceSettings.pitch();
            speed = voiceSettings.speed();
            accent = voiceSettings.accent();
            lmd = voiceSettings.lmd();
        }

        text = expandMentions(text, guild);
        text = URL_PATTERN.matcher(text).replaceAll("ユーアールエル省略");
        text = stripCustomEmoji(text);
        text = stripUnicodeEmoji(text);
        text = text.replace(" ", "");

        if (text.isEmpty()) {
            return;
        }
        if (text.length() >= config.discord.max_length) {
            text = text.substring(0, config.discord.max_length) + "。以下省略";
        }

        Database.VoiceSettings finalSettings = new Database.VoiceSettings(engine, voiceName, pitch, speed, accent, lmd);
        BlockingQueue<QueuedMessage> queue = messageQueues.computeIfAbsent(
                guild.getIdLong(), id -> new LinkedBlockingQueue<>()
        );
        queue.offer(new QueuedMessage(text, finalSettings, audioManager));

        readingTasks.computeIfAbsent(guild.getIdLong(), id -> CompletableFuture.runAsync(
                () -> processMessageQueue(guild.getIdLong()),
                executor
        ));
    }

    private void processMessageQueue(long guildId) {
        BlockingQueue<QueuedMessage> queue = messageQueues.get(guildId);
        if (queue == null) {
            return;
        }
        long versionAtStart = skipVersion.getOrDefault(guildId, 0L);
        try {
            while (true) {
                QueuedMessage item = queue.take();
                if (item.isPoison()) {
                    break;
                }
                if (isSkipped(guildId, versionAtStart)) {
                    continue;
                }
                speak(item, guildId, versionAtStart);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("メッセージキュー処理エラー", e);
        } finally {
            readingTasks.remove(guildId);
        }
    }

    private boolean isSkipped(long guildId, long versionAtStart) {
        return skipVersion.getOrDefault(guildId, 0L) > versionAtStart;
    }

    private void speak(QueuedMessage item, long guildId, long versionAtStart) {
        AudioManager audioManager = item.audioManager();
        if (!audioManager.isConnected() || isSkipped(guildId, versionAtStart)) {
            return;
        }

        String message = TextConverters.wToWaraConverter(
                Normalizer.normalize(
                        TextConverters.romajiToHiragana(item.settings().engine().startsWith("aquestalk")
                                ? item.text()
                                : item.text().toLowerCase(Locale.ROOT)),
                        Normalizer.Form.NFKC
                )
        );

        Database.VoiceSettings settings = item.settings();
        String engineName = settings.engine();
        if (!engineFactory.isEngineEnabled(engineName)) {
            return;
        }

        if (config.debug) {
            log.debug("音声合成開始: {} - 使用する音声合成エンジン: {}", message, engineName);
        }
        long synthesisStartNanos = config.debug ? System.nanoTime() : 0;

        try {
            VoiceEngine engine = engineFactory.getEngine(engineName);
            byte[] audioData = engine.synthesize(message, settings);
            if (isSkipped(guildId, versionAtStart)) {
                return;
            }
            if (engineName.startsWith("aquestalk") && !"aquestalk10".equals(engineName)) {
                audioData = SoundCoreUtil.pitchConvert(audioData, settings.pitch());
            }
            if (config.debug) {
                double seconds = (System.nanoTime() - synthesisStartNanos) / 1_000_000_000.0;
                log.debug("音声合成完了 - 所要時間: {}秒", seconds);
            }
            byte[] pcm = SoundCoreUtil.toDiscordPcm(audioData);
            if (isSkipped(guildId, versionAtStart)) {
                return;
            }

            PcmAudioPlayer player = new PcmAudioPlayer();
            activePlayers.put(guildId, player);
            try {
                player.load(pcm);
                audioManager.setSendingHandler(player);
                player.onFinished().get(10, TimeUnit.MINUTES);
                if (config.debug) {
                    log.debug("音声再生が完了しました");
                }
            } catch (Exception playback) {
                if (!isSkipped(guildId, versionAtStart)) {
                    log.error("音声再生エラー。入力メッセージ: {}", message, playback);
                }
                return;
            } finally {
                activePlayers.remove(guildId, player);
            }
        } catch (Exception primary) {
            if (isSkipped(guildId, versionAtStart)) {
                return;
            }
            log.error("音声合成エラー。入力メッセージ: {}", message, primary);
            try {
                fallbackSpeak(message, audioManager, guildId, versionAtStart);
            } catch (Exception fallback) {
                if (!isSkipped(guildId, versionAtStart)) {
                    log.error("フォールバック音声合成エラー", fallback);
                }
            }
        }
    }

    private void fallbackSpeak(String message, AudioManager audioManager, long guildId, long versionAtStart)
            throws Exception {
        if (isSkipped(guildId, versionAtStart)) {
            return;
        }
        Database.VoiceSettings fallbackSettings = new Database.VoiceSettings(
                "aquestalk10", "F1E", 100, 100, 100, 100
        );
        byte[] audioData = engineFactory.getEngine("aquestalk10").synthesize(message, fallbackSettings);
        byte[] pcm = SoundCoreUtil.toDiscordPcm(audioData);
        if (isSkipped(guildId, versionAtStart)) {
            return;
        }
        PcmAudioPlayer player = new PcmAudioPlayer();
        activePlayers.put(guildId, player);
        try {
            player.load(pcm);
            audioManager.setSendingHandler(player);
            player.onFinished().get(10, TimeUnit.MINUTES);
        } catch (Exception playback) {
            if (!isSkipped(guildId, versionAtStart)) {
                log.error("フォールバック音声再生エラー。入力メッセージ: {}", message, playback);
            }
        } finally {
            activePlayers.remove(guildId, player);
        }
    }

    private String expandMentions(String message, Guild guild) {
        Matcher userMatcher = USER_MENTION.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (userMatcher.find()) {
            long userId = Long.parseLong(userMatcher.group(1));
            Member member = guild.getMemberById(userId);
            if (member == null) {
                try {
                    member = guild.retrieveMemberById(userId).complete();
                } catch (Exception ignored) {
                    member = null;
                }
            }
            userMatcher.appendReplacement(buffer, member != null ? member.getEffectiveName() : "ユーザー");
        }
        userMatcher.appendTail(buffer);
        message = buffer.toString();

        for (String roleId : ROLE_MENTION.matcher(message).results().map(m -> m.group(1)).toList()) {
            Role role = guild.getRoleById(Long.parseLong(roleId));
            if (role != null) {
                message = message.replace("<@&" + roleId + ">", role.getName());
            }
        }

        Matcher channelMatcher = CHANNEL_MENTION.matcher(message);
        buffer = new StringBuffer();
        while (channelMatcher.find()) {
            String channelId = channelMatcher.group(1);
            TextChannel channel = guild.getTextChannelById(Long.parseLong(channelId));
            String replacement = channel != null ? stripUnicodeEmoji(channel.getName()) : "チャンネル";
            channelMatcher.appendReplacement(buffer, replacement);
        }
        channelMatcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String stripCustomEmoji(String text) {
        text = CUSTOM_EMOJI_MENTION.matcher(text).replaceAll("");
        return CUSTOM_EMOJI_SHORTCODE.matcher(text).replaceAll("");
    }

    private static String stripUnicodeEmoji(String text) {
        return EMOJI_PATTERN.matcher(text).replaceAll("");
    }

    private static String applyReplacements(String message, Map<String, String> replacements) {
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private static long guildKey(long guildId, long userId) {
        return guildId ^ (userId << 32);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public record QueuedMessage(String text, Database.VoiceSettings settings, AudioManager audioManager, boolean isPoison) {
        QueuedMessage(String text, Database.VoiceSettings settings, AudioManager audioManager) {
            this(text, settings, audioManager, false);
        }

        static QueuedMessage poison() {
            return new QueuedMessage("", null, null, true);
        }
    }
}
