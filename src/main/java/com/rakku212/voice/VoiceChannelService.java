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

    private final BotConfig config;
    private final Database database;
    private final VoiceEngineFactory engineFactory;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, BlockingQueue<QueuedMessage>> messageQueues = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> readingTasks = new ConcurrentHashMap<>();
    private final Map<Long, Database.VoiceSettings> currentVoiceSettings = new ConcurrentHashMap<>();

    public VoiceChannelService(Database database) {
        this.config = Config.load();
        this.database = database;
        this.engineFactory = new VoiceEngineFactory(config);
    }

    public void updateVoiceSettings(long guildId, long userId, Database.VoiceSettings settings) {
        currentVoiceSettings.put(guildKey(guildId, userId), settings);
    }

    public void clearQueue(long guildId) {
        BlockingQueue<QueuedMessage> queue = messageQueues.remove(guildId);
        if (queue != null) {
            queue.clear();
            queue.offer(QueuedMessage.poison());
        }
        CompletableFuture<Void> task = readingTasks.remove(guildId);
        if (task != null) {
            task.cancel(true);
        }
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
        try {
            while (true) {
                QueuedMessage item = queue.take();
                if (item.isPoison()) {
                    break;
                }
                speak(item);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("メッセージキュー処理エラー", e);
        }
    }

    private void speak(QueuedMessage item) {
        AudioManager audioManager = item.audioManager();
        if (!audioManager.isConnected()) {
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
            if (engineName.startsWith("aquestalk") && !"aquestalk10".equals(engineName)) {
                audioData = SoundCoreUtil.pitchConvert(audioData, settings.pitch());
            }
            if (config.debug) {
                double seconds = (System.nanoTime() - synthesisStartNanos) / 1_000_000_000.0;
                log.debug("音声合成完了 - 所要時間: {}秒", seconds);
            }
            byte[] pcm = SoundCoreUtil.toDiscordPcm(audioData);

            PcmAudioPlayer player = new PcmAudioPlayer();
            player.load(pcm);
            audioManager.setSendingHandler(player);
            player.onFinished().get(10, TimeUnit.MINUTES);
            if (config.debug) {
                log.debug("音声再生が完了しました");
            }
        } catch (Exception primary) {
            log.error("音声合成エラー: {}\n入力メッセージ: {}", primary.getMessage(), message);
            try {
                fallbackSpeak(message, audioManager);
            } catch (Exception fallback) {
                log.error("フォールバック音声合成エラー", fallback);
            }
        }
    }

    private void fallbackSpeak(String message, AudioManager audioManager) throws Exception {
        Database.VoiceSettings fallbackSettings = new Database.VoiceSettings(
                "aquestalk10", "F1E", 100, 100, 100, 100
        );
        byte[] audioData = engineFactory.getEngine("aquestalk10").synthesize(message, fallbackSettings);
        byte[] pcm = SoundCoreUtil.toDiscordPcm(audioData);
        PcmAudioPlayer player = new PcmAudioPlayer();
        player.load(pcm);
        audioManager.setSendingHandler(player);
        player.onFinished().get(10, TimeUnit.MINUTES);
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
            String replacement = channel != null ? EMOJI_PATTERN.matcher(channel.getName()).replaceAll("") : "チャンネル";
            channelMatcher.appendReplacement(buffer, replacement);
        }
        channelMatcher.appendTail(buffer);
        return buffer.toString();
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
