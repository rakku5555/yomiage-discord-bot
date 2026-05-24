package com.rakku212.command;

import com.rakku212.config.BotConfig;
import com.rakku212.config.Config;
import com.rakku212.database.Database;
import com.rakku212.util.EmbedUtil;
import com.rakku212.util.JapaneseUtil;
import com.rakku212.voice.VoiceChannelService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class CommandManager extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CommandManager.class);
    private static final int DICT_PAGE_SIZE = 10;

    private final BotConfig config;
    private final Database database;
    private final VoiceChannelService voiceChannelService;
    private final VoiceCharacterCatalog voiceCatalog;

    public CommandManager(Database database, VoiceChannelService voiceChannelService) {
        this.config = Config.load();
        this.database = database;
        this.voiceChannelService = voiceChannelService;
        this.voiceCatalog = new VoiceCharacterCatalog();
    }

    public void registerCommands(JDA jda) {
        jda.updateCommands()
                .addCommands(
                        Commands.slash("join", "ボイスチャンネルに参加"),
                        Commands.slash("leave", "ボイスチャンネルから退出"),
                        autojoinCommand(),
                        setvoiceCommand(),
                        Commands.slash("skip", "現在の読み上げをすべてスキップします"),
                        dictCommand(),
                        globalDictCommand(),
                        Commands.slash("mute", "特定のユーザーのメッセージを読み上げません")
                                .addOption(OptionType.USER, "user", "読み上げを停止するユーザー", true),
                        Commands.slash("unmute", "ミュートされたユーザーのメッセージを読み上げるようにします")
                                .addOption(OptionType.USER, "user", "読み上げを再開するユーザー", true),
                        Commands.slash("mutelist", "ミュートされているユーザーの一覧を表示します"),
                        dynamicJoinCommand(),
                        Commands.slash("change", "読み上げるテキストチャンネルを変更します")
                )
                .queue(
                        success -> {
                            log.info("{} 件のスラッシュコマンドを登録しました", success.size());
                            if (config.debug) {
                                log.debug("コマンド同期完了");
                            }
                        },
                        error -> log.error("スラッシュコマンドの登録に失敗しました", error)
                );
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply(EmbedUtil.error("このコマンドはサーバー内でのみ使用できます。"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        try {
            switch (event.getName()) {
                case "join" -> handleJoin(event);
                case "leave" -> handleLeave(event);
                case "autojoin" -> handleAutojoin(event);
                case "setvoice" -> handleSetvoice(event);
                case "skip" -> handleSkip(event);
                case "dict" -> handleDict(event);
                case "global_dict" -> handleGlobalDict(event);
                case "mute" -> handleMute(event);
                case "unmute" -> handleUnmute(event);
                case "mutelist" -> handleMutelist(event);
                case "dynamic_join" -> handleDynamicJoin(event);
                case "change" -> handleChange(event);
                default -> event.reply(EmbedUtil.error("未対応のコマンドです。"))
                        .setEphemeral(true)
                        .queue();
            }
        } catch (Exception e) {
            log.error("コマンド処理エラー: /{}", event.getName(), e);
            reply(event, EmbedUtil.error("コマンドの処理中にエラーが発生しました: " + e.getMessage()), true);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!"setvoice".equals(event.getName()) || !"voice".equals(event.getFocusedOption().getName())) {
            return;
        }
        String engine = event.getOption("engine") != null
                ? event.getOption("engine").getAsString()
                : "";
        if (engine.isBlank() || !VoiceCharacterCatalog.ENGINE_KEY.containsKey(engine)) {
            event.replyChoices(Collections.emptyList()).queue();
            return;
        }

        String current = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        for (VoiceCharacterCatalog.VoiceEntry voice : voiceCatalog.getVoices(engine)) {
            if (!current.isEmpty() && !voice.name().toLowerCase(Locale.ROOT).contains(current)) {
                continue;
            }
            choices.add(new Command.Choice(voice.name(), voice.value()));
        }
        if (choices.size() > 25) {
            Collections.shuffle(choices, ThreadLocalRandom.current());
            choices = choices.subList(0, 25);
        }
        event.replyChoices(choices).queue();
    }

    private static void reply(SlashCommandInteractionEvent event, MessageCreateData message, boolean ephemeral) {
        if (event.isAcknowledged()) {
            event.getHook().sendMessage(message).setEphemeral(ephemeral).queue();
        } else {
            event.reply(message).setEphemeral(ephemeral).queue();
        }
    }

    private static void reply(SlashCommandInteractionEvent event, MessageCreateData message) {
        reply(event, message, false);
    }

    private static VoiceChannel memberVoiceChannel(Member member) {
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            return null;
        }
        return member.getVoiceState().getChannel().asVoiceChannel();
    }

    private void handleJoin(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = memberVoiceChannel(member);
        if (voiceChannel == null) {
            reply(event, EmbedUtil.error("ボイスチャンネルに接続していません。"), true);
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            reply(event, EmbedUtil.error("すでに" + voiceChannel.getAsMention() + "に接続しています。"), true);
            return;
        }

        try {
            audioManager.openAudioConnection(voiceChannel);
            audioManager.setSelfDeafened(true);
            database.setReadChannel(guild.getIdLong(), voiceChannel.getIdLong(), event.getChannel().getIdLong());
            reply(event, EmbedUtil.success(
                    voiceChannel.getAsMention() + "に参加しました！このチャンネルのメッセージを読み上げます。"
            ));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("ボイスチャンネルへの参加に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleLeave(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            reply(event, EmbedUtil.error("ボイスチャンネルに接続していません。"), true);
            return;
        }

        try {
            voiceChannelService.clearQueue(guild.getIdLong());
            audioManager.closeAudioConnection();
            database.removeReadChannel(guild.getIdLong());
            reply(event, EmbedUtil.success("ボイスチャンネルから退出しました！"));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("ボイスチャンネルから退出できませんでした。"), true);
        }
    }

    private void handleAutojoin(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            reply(event, EmbedUtil.error("サブコマンドが指定されていません。"), true);
            return;
        }

        try {
            switch (sub) {
                case "add" -> {
                    VoiceChannel voice = event.getOption("voice", o -> o.getAsChannel().asVoiceChannel());
                    TextChannel text = event.getOption("text", o -> o.getAsChannel().asTextChannel());
                    if (voice == null || text == null) {
                        reply(event, EmbedUtil.error("チャンネルが指定されていません。"), true);
                        return;
                    }
                    database.setAutojoin(guild.getIdLong(), voice.getIdLong(), text.getIdLong());
                    reply(event, EmbedUtil.success(
                            "自動参加設定を追加しました。\n"
                                    + "ボイスチャンネル: " + voice.getAsMention() + "\n"
                                    + "テキストチャンネル: " + text.getAsMention()
                    ));
                }
                case "remove" -> {
                    VoiceChannel voice = event.getOption("voice").getAsChannel().asVoiceChannel();
                    Optional<Database.ReadChannel> autojoin = database.getAutojoin(guild.getIdLong());
                    if (autojoin.isEmpty()) {
                        reply(event, EmbedUtil.warning("自動参加設定はありません。"), true);
                        return;
                    }
                    if (voice.getIdLong() != autojoin.get().voiceChannelId()) {
                        reply(event, EmbedUtil.error(
                                "指定されたボイスチャンネル " + voice.getAsMention() + " の自動参加設定はありません。"
                        ), true);
                        return;
                    }
                    database.removeAutojoin(guild.getIdLong());
                    reply(event, EmbedUtil.success("ボイスチャンネル " + voice.getAsMention() + " の自動参加設定を削除しました。"));
                }
                case "list" -> {
                    Optional<Database.ReadChannel> autojoin = database.getAutojoin(guild.getIdLong());
                    if (autojoin.isEmpty()) {
                        reply(event, EmbedUtil.warning("自動参加設定はありません。"), true);
                        return;
                    }
                    var voiceChannel = guild.getVoiceChannelById(autojoin.get().voiceChannelId());
                    var textChannel = guild.getTextChannelById(autojoin.get().chatChannelId());
                    if (voiceChannel == null || textChannel == null) {
                        reply(event, EmbedUtil.error("設定されたチャンネルが見つかりません。"), true);
                        return;
                    }
                    reply(event, EmbedUtil.success(
                            "現在の自動参加設定:\n"
                                    + "ボイスチャンネル: " + voiceChannel.getName() + "\n"
                                    + "テキストチャンネル: " + textChannel.getName()
                    ));
                }
                default -> reply(event, EmbedUtil.error("未対応のサブコマンドです。"), true);
            }
        } catch (Exception e) {
            reply(event, EmbedUtil.error("設定の更新に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleSetvoice(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null || event.getUser() == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        String engine = Optional.ofNullable(event.getOption("engine"))
                .map(OptionMapping::getAsString)
                .orElse("");
        String voice = Optional.ofNullable(event.getOption("voice"))
                .map(OptionMapping::getAsString)
                .orElse("");
        int pitch = Optional.ofNullable(event.getOption("pitch"))
                .map(OptionMapping::getAsInt)
                .orElse(100);
        double speed = Optional.ofNullable(event.getOption("speed"))
                .map(OptionMapping::getAsDouble)
                .orElse(1.0);
        int accent = Optional.ofNullable(event.getOption("accent"))
                .map(OptionMapping::getAsInt)
                .orElse(100);
        int lmd = Optional.ofNullable(event.getOption("lmd"))
                .map(OptionMapping::getAsInt)
                .orElse(100);

        if (engine.startsWith("aquestalk")) {
            if (speed == 1.0) {
                speed = 100;
            }
            if (speed < 50 || speed > 200) {
                reply(event, EmbedUtil.error("AquesTalkの速度は50から200の間で指定してください。"), true);
                return;
            }
        } else if (speed < 0.5 || speed > 5) {
            reply(event, EmbedUtil.error("VOICEVOX/AivisSpeechの速度は0.5から5の間で指定してください。"), true);
            return;
        }

        Optional<String> validationError = voiceCatalog.validate(engine, voice, config);
        if (validationError.isPresent()) {
            reply(event, EmbedUtil.error(validationError.get()), true);
            return;
        }

        try {
            Database.VoiceSettings settings = new Database.VoiceSettings(engine, voice, pitch, speed, accent, lmd);
            database.setVoiceSettings(guild.getIdLong(), event.getUser().getIdLong(), engine, voice, pitch, speed, accent, lmd);
            voiceChannelService.updateVoiceSettings(guild.getIdLong(), event.getUser().getIdLong(), settings);

            String voiceName = voiceCatalog.getVoiceName(engine, voice);
            String speedText = speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
            StringBuilder message = new StringBuilder("ボイス設定を更新しました。\n")
                    .append("エンジン: ").append(engine).append('\n')
                    .append("キャラクター: ").append(voiceName).append('\n')
                    .append("声の高さ: ").append(pitch).append('\n')
                    .append("速度: ").append(speedText).append('\n');
            if ("voicevox".equals(engine)) {
                message.append("VOICEVOX: ").append(voiceName);
            }
            if ("aquestalk10".equals(engine)) {
                message.append("アクセントの強さ: ").append(accent).append('\n')
                        .append("声質の高低: ").append(lmd);
            }
            reply(event, EmbedUtil.info(message.toString()));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("設定の更新に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleSkip(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }
        if (!guild.getAudioManager().isConnected()) {
            reply(event, EmbedUtil.error("ボイスチャンネルに接続していません。"), true);
            return;
        }
        voiceChannelService.clearQueue(guild.getIdLong());
        reply(event, EmbedUtil.success("読み上げを停止しました。"));
    }

    private void handleDict(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            reply(event, EmbedUtil.error("サブコマンドが指定されていません。"), true);
            return;
        }

        try {
            switch (sub) {
                case "add" -> {
                    String word = event.getOption("word").getAsString().toLowerCase(Locale.ROOT);
                    String to = JapaneseUtil.hiraToKatakana(event.getOption("to").getAsString());
                    database.setDictionaryReplacement(guild.getIdLong(), word, to);
                    reply(event, EmbedUtil.info("単語を登録しました。\n「" + word + "」→「" + to + "」"));
                }
                case "list" -> {
                    Map<String, String> replacements = database.getDictionaryReplacements(guild.getIdLong());
                    if (replacements.isEmpty()) {
                        reply(event, EmbedUtil.warning("登録されている単語はありません。"), true);
                        return;
                    }
                    reply(event, formatDictionaryEmbed("登録されている単語一覧", replacements));
                }
                case "remove" -> {
                    String word = event.getOption("word").getAsString().toLowerCase(Locale.ROOT);
                    Map<String, String> replacements = database.getDictionaryReplacements(guild.getIdLong());
                    if (!replacements.containsKey(word)) {
                        reply(event, EmbedUtil.error("単語「" + word + "」は登録されていません。"), true);
                        return;
                    }
                    database.removeDictionaryReplacement(guild.getIdLong(), word);
                    reply(event, EmbedUtil.embed(new java.awt.Color(0x9B59B6), "単語「" + word + "」を削除しました。"));
                }
                default -> reply(event, EmbedUtil.error("未対応のサブコマンドです。"), true);
            }
        } catch (Exception e) {
            reply(event, EmbedUtil.error("辞書コマンドの処理に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleGlobalDict(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            reply(event, EmbedUtil.error("サブコマンドが指定されていません。"), true);
            return;
        }

        try {
            switch (sub) {
                case "add" -> {
                    String word = event.getOption("word").getAsString().toLowerCase(Locale.ROOT);
                    String to = JapaneseUtil.hiraToKatakana(event.getOption("to").getAsString());
                    database.setGlobalDictionaryReplacement(word, to);
                    reply(event, EmbedUtil.info("単語を登録しました。\n「" + word + "」→「" + to + "」"));
                }
                case "list" -> {
                    Map<String, String> replacements = database.getGlobalDictionaryReplacements();
                    if (replacements.isEmpty()) {
                        reply(event, EmbedUtil.warning("登録されている単語はありません。"), true);
                        return;
                    }
                    reply(event, formatDictionaryEmbed("グローバル辞書一覧", replacements));
                }
                default -> reply(event, EmbedUtil.error("未対応のサブコマンドです。"), true);
            }
        } catch (Exception e) {
            reply(event, EmbedUtil.error("グローバル辞書コマンドの処理に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleMute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }
        Member user = event.getOption("user").getAsMember();
        if (user == null) {
            reply(event, EmbedUtil.error("ユーザーが見つかりません。"), true);
            return;
        }
        if (user.getUser().isBot()) {
            reply(event, EmbedUtil.error("ボットのメッセージは読み上げられません。"), true);
            return;
        }

        try {
            if (database.isUserMuted(guild.getIdLong(), user.getIdLong())) {
                reply(event, EmbedUtil.warning(user.getEffectiveName() + "はすでにミュートされています。"), true);
                return;
            }
            database.setMutedUser(guild.getIdLong(), user.getIdLong());
            reply(event, EmbedUtil.success(user.getEffectiveName() + "のメッセージを読み上げないように設定しました。"));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("ミュート設定に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleUnmute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }
        Member user = event.getOption("user").getAsMember();
        if (user == null) {
            reply(event, EmbedUtil.error("ユーザーが見つかりません。"), true);
            return;
        }

        try {
            if (!database.isUserMuted(guild.getIdLong(), user.getIdLong())) {
                reply(event, EmbedUtil.warning(user.getEffectiveName() + "はミュートになっていません。"), true);
                return;
            }
            database.removeMutedUser(guild.getIdLong(), user.getIdLong());
            reply(event, EmbedUtil.success(user.getEffectiveName() + "のメッセージを読み上げるように設定しました。"));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("ミュート解除に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleMutelist(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        try {
            List<Long> mutedUsers = database.getMutedUsers(guild.getIdLong());
            if (mutedUsers.isEmpty()) {
                reply(event, EmbedUtil.warning("ミュートされているユーザーはいません。"), true);
                return;
            }
            StringBuilder message = new StringBuilder("ミュートされているユーザー一覧:\n");
            for (long userId : mutedUsers) {
                Member member = guild.getMemberById(userId);
                if (member != null) {
                    message.append("- ").append(member.getEffectiveName()).append('\n');
                }
            }
            reply(event, EmbedUtil.embed(new java.awt.Color(0x9B59B6), message.toString()), true);
        } catch (Exception e) {
            reply(event, EmbedUtil.error("ミュートユーザー一覧の取得に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleDynamicJoin(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            reply(event, EmbedUtil.error("サブコマンドが指定されていません。"), true);
            return;
        }

        try {
            switch (sub) {
                case "add" -> {
                    TextChannel text = event.getOption("text").getAsChannel().asTextChannel();
                    database.setDynamicJoin(guild.getIdLong(), text.getIdLong());
                    reply(event, EmbedUtil.success(
                            "動的自動参加の設定を追加しました。\nテキストチャンネル: " + text.getAsMention()
                    ));
                }
                case "remove" -> {
                    TextChannel text = event.getOption("text").getAsChannel().asTextChannel();
                    database.removeDynamicJoin(guild.getIdLong(), text.getIdLong());
                    reply(event, EmbedUtil.success(
                            "動的自動参加の設定を削除しました。\nテキストチャンネル: " + text.getAsMention()
                    ));
                }
                case "list" -> {
                    List<Long> dynamicJoins = database.getDynamicJoins(guild.getIdLong());
                    if (dynamicJoins.isEmpty()) {
                        reply(event, EmbedUtil.warning("動的自動参加の設定はありません。"), true);
                        return;
                    }
                    StringBuilder message = new StringBuilder("動的自動参加の設定一覧:\n");
                    for (long textId : dynamicJoins) {
                        TextChannel text = guild.getTextChannelById(textId);
                        if (text != null) {
                            message.append("- テキストチャンネル: ").append(text.getName()).append('\n');
                        }
                    }
                    reply(event, EmbedUtil.embed(new java.awt.Color(0x9B59B6), message.toString()), true);
                }
                default -> reply(event, EmbedUtil.error("未対応のサブコマンドです。"), true);
            }
        } catch (Exception e) {
            reply(event, EmbedUtil.error("動的自動参加コマンドの処理に失敗しました: " + e.getMessage()), true);
        }
    }

    private void handleChange(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, EmbedUtil.error("サーバー情報が取得できません。"), true);
            return;
        }

        try {
            Optional<Database.ReadChannel> readChannel = database.getReadChannel(guild.getIdLong());
            if (readChannel.isEmpty()) {
                reply(event, EmbedUtil.error(
                        "読み上げチャンネルが設定されていません。先に/joinコマンドでボイスチャンネルに参加してください。"
                ), true);
                return;
            }
            database.setReadChannel(
                    guild.getIdLong(),
                    readChannel.get().voiceChannelId(),
                    event.getChannel().getIdLong()
            );
            reply(event, EmbedUtil.success("読み上げるテキストチャンネルを変更しました。"));
        } catch (Exception e) {
            reply(event, EmbedUtil.error("読み上げるテキストチャンネルの変更に失敗しました: " + e.getMessage()), true);
        }
    }

    private static MessageCreateData formatDictionaryEmbed(String title, Map<String, String> replacements) {
        List<Map.Entry<String, String>> items = new ArrayList<>(replacements.entrySet());
        int totalPages = Math.max(1, (int) Math.ceil(items.size() / (double) DICT_PAGE_SIZE));
        StringBuilder message = new StringBuilder(title).append(" (ページ 1/").append(totalPages).append("):\n");
        int end = Math.min(DICT_PAGE_SIZE, items.size());
        for (int i = 0; i < end; i++) {
            Map.Entry<String, String> entry = items.get(i);
            message.append("- 「").append(entry.getKey()).append("」→「").append(entry.getValue()).append("」\n");
        }
        return EmbedUtil.embed(new java.awt.Color(0x9B59B6), message.toString());
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData autojoinCommand() {
        return Commands.slash("autojoin", "自動参加機能の設定")
                .addSubcommands(
                        new SubcommandData("add", "自動参加するボイスチャンネルと読み上げるテキストチャンネルを設定します")
                                .addOptions(
                                        voiceChannelOption("voice", "参加するボイスチャンネル", true),
                                        textChannelOption("text", "読み上げるテキストチャンネル", true)
                                ),
                        new SubcommandData("remove", "自動参加設定を削除します")
                                .addOptions(voiceChannelOption("voice", "削除するボイスチャンネル", true)),
                        new SubcommandData("list", "現在の自動参加設定一覧を表示します")
                );
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData dictCommand() {
        return Commands.slash("dict", "辞書機能の設定")
                .addSubcommands(
                        new SubcommandData("add", "単語の読み方を登録します")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "word", "登録する単語", true),
                                        new OptionData(OptionType.STRING, "to", "変換後の読み方", true)
                                ),
                        new SubcommandData("list", "登録されている単語の一覧を表示します"),
                        new SubcommandData("remove", "登録されている単語を削除します")
                                .addOption(OptionType.STRING, "word", "削除する単語", true)
                );
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData globalDictCommand() {
        return Commands.slash("global_dict", "グローバル辞書機能の設定")
                .addSubcommands(
                        new SubcommandData("add", "グローバル単語の読み方を登録します")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "word", "登録する単語", true),
                                        new OptionData(OptionType.STRING, "to", "変換後の読み方", true)
                                ),
                        new SubcommandData("list", "グローバル登録されている単語の一覧を表示します")
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData dynamicJoinCommand() {
        return Commands.slash("dynamic_join", "動的自動参加の設定")
                .addSubcommands(
                        new SubcommandData("add", "動的自動参加の設定を追加します")
                                .addOptions(textChannelOption("text", "読み上げるテキストチャンネル", true)),
                        new SubcommandData("remove", "動的自動参加の設定を削除します")
                                .addOptions(textChannelOption("text", "削除するテキストチャンネル", true)),
                        new SubcommandData("list", "動的自動参加の設定一覧を表示します")
                );
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData setvoiceCommand() {
        return Commands.slash("setvoice", "ボイスキャラクターと読み上げ速度を設定します")
                .addOptions(
                        new OptionData(OptionType.STRING, "engine", "音声エンジンの選択", true)
                                .addChoice("AquesTalk1", "aquestalk1")
                                .addChoice("AquesTalk2", "aquestalk2")
                                .addChoice("AquesTalk10", "aquestalk10")
                                .addChoice("VOICEVOX", "voicevox")
                                .addChoice("AivisSpeech", "aivisspeech"),
                        new OptionData(OptionType.STRING, "voice", "声の指定", true).setAutoComplete(true),
                        new OptionData(OptionType.INTEGER, "pitch", "声の高さ (デフォルトは100)", false),
                        new OptionData(OptionType.NUMBER, "speed", "読み上げ速度 (AquesTalk: 50-200, VOICEVOX/AivisSpeech: 0.5-5)", false),
                        new OptionData(OptionType.INTEGER, "accent", "アクセントの強さ (AquesTalk10のみ デフォルト: 100)", false),
                        new OptionData(OptionType.INTEGER, "lmd", "声質の高低 (AquesTalk10のみ デフォルト: 100)", false)
                );
    }

    private static OptionData voiceChannelOption(String name, String description, boolean required) {
        return new OptionData(OptionType.CHANNEL, name, description, required)
                .setChannelTypes(ChannelType.VOICE);
    }

    private static OptionData textChannelOption(String name, String description, boolean required) {
        return new OptionData(OptionType.CHANNEL, name, description, required)
                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS);
    }
}
