package com.rakku212.listener;

import com.rakku212.config.BotConfig;
import com.rakku212.database.Database;
import com.rakku212.voice.VoiceChannelService;
import com.rakku212.voice.VoiceConnectionHelper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BotListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(BotListener.class);

    private final BotConfig config;
    private final Database database;
    private final VoiceChannelService voiceChannelService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private net.dv8tion.jda.api.JDA jda;

    public BotListener(BotConfig config, Database database, VoiceChannelService voiceChannelService) {
        this.config = config;
        this.database = database;
        this.voiceChannelService = voiceChannelService;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        this.jda = event.getJDA();
        if (config.debug) {
            log.debug("デバッグモードが有効です");
        }
        restoreReadChannels(event.getJDA());
        scheduler.scheduleAtFixedRate(this::cleanupDisconnectedChannels, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        if (event.isFromGuild()) {
            try {
                if (database.isUserMuted(event.getGuild().getIdLong(), event.getAuthor().getIdLong())) {
                    return;
                }
            } catch (Exception e) {
                log.error("ミュート確認エラー", e);
                return;
            }
            voiceChannelService.readMessage(event.getMessage());
            return;
        }
        event.getChannel().sendMessageEmbeds(new EmbedBuilder()
                .setColor(Color.RED)
                .setDescription("このBOTはここでは使用できません。")
                .build()).queue();
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        Member member = event.getMember();
        Guild guild = event.getGuild();
        var channelLeft = event.getChannelLeft();
        var channelJoined = event.getChannelJoined();
        VoiceChannel before = channelLeft != null ? channelLeft.asVoiceChannel() : null;
        VoiceChannel after = channelJoined != null ? channelJoined.asVoiceChannel() : null;

        if (member.getUser().equals(guild.getSelfMember().getUser())) {
            handleSelfVoiceMove(guild, before, after);
            return;
        }

        if (before == null && after != null) {
            handleMemberJoin(member, after);
        }

        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected() || audioManager.getConnectedChannel() == null) {
            return;
        }

        VoiceChannel connectedChannel = audioManager.getConnectedChannel().asVoiceChannel();
        int memberCount = countNonBotMembers(connectedChannel);

        if (before == null && after != null
                && connectedChannel.getIdLong() == after.getIdLong() && memberCount > 1) {
            voiceChannelService.readMessage(member.getEffectiveName() + "が参加しました", guild, member);
            if (config.debug) {
                log.debug("{}に{}が参加しました", guild.getName(), member.getEffectiveName());
            }
        } else if (after == null
                && connectedChannel.getIdLong() == (before != null ? before.getIdLong() : -1)
                && memberCount >= 1) {
            voiceChannelService.readMessage(member.getEffectiveName() + "が退出しました", guild, member);
            if (config.debug) {
                log.debug("{}に{}が退出しました", guild.getName(), member.getEffectiveName());
            }
        }

        if (memberCount > 0) {
            return;
        }

        database.getReadChannel(guild.getIdLong()).ifPresent(readChannel -> {
            VoiceConnectionHelper.disconnect(guild, voiceChannelService);
            TextChannel chatChannel = guild.getTextChannelById(readChannel.chatChannelId());
            if (chatChannel != null) {
                chatChannel.sendMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(0x00008B))
                        .setDescription(connectedChannel.getAsMention() + "からユーザーがいなくなったため退出しました")
                        .build()).queue();
            }
            database.removeReadChannel(guild.getIdLong());
            if (config.debug) {
                log.debug("{}のボイスチャンネルのメンバーはいないため読み上げチャンネルから切断しました",
                        guild.getName());
            }
        });
    }

    private void handleSelfVoiceMove(Guild guild, VoiceChannel before, VoiceChannel after) {
        if (before == null || after == null || before.getIdLong() == after.getIdLong()) {
            return;
        }
        database.getReadChannel(guild.getIdLong()).ifPresent(readChannel -> {
            database.setReadChannel(guild.getIdLong(), after.getIdLong(), readChannel.chatChannelId());
            if (config.debug) {
                log.debug("{}の読み上げチャンネルをデータベースから更新しました", guild.getName());
            }
        });
    }

    private void handleMemberJoin(Member member, VoiceChannel after) {
        Guild guild = member.getGuild();
        try {
            database.getAutojoin(guild.getIdLong()).ifPresent(autojoin -> {
                if (after.getIdLong() == autojoin.voiceChannelId()
                        && countNonBotMembers(after) == 1
                        && !guild.getAudioManager().isConnected()) {
                    connectToVoiceChannel(guild, after, autojoin.chatChannelId(), member);
                }
            });

            List<Long> dynamicJoins = database.getDynamicJoins(guild.getIdLong());
            if (dynamicJoins.isEmpty() || guild.getAudioManager().isConnected()) {
                return;
            }

            scheduler.schedule(() -> {
                VoiceChannel channel = guild.getVoiceChannelById(after.getIdLong());
                if (channel == null || countNonBotMembers(channel) == 0 || guild.getAudioManager().isConnected()) {
                    return;
                }
                if (Duration.between(channel.getTimeCreated(), OffsetDateTime.now()).toMinutes() > 5) {
                    return;
                }
                for (long textChannelId : dynamicJoins) {
                    TextChannel textChannel = guild.getTextChannelById(textChannelId);
                    if (textChannel == null) {
                        continue;
                    }
                    if (textChannel.getParentCategory() != null
                            && channel.getParentCategory() != null
                            && textChannel.getParentCategory().getIdLong()
                            != channel.getParentCategory().getIdLong()) {
                        continue;
                    }
                    connectToVoiceChannel(guild, channel, textChannelId, member);
                    break;
                }
            }, 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("{}のボイス参加処理エラー", guild.getName(), e);
        }
    }

    private void connectToVoiceChannel(Guild guild, VoiceChannel voiceChannel, long textChannelId, Member member) {
        try {
            guild.getAudioManager().openAudioConnection(voiceChannel);
            guild.getAudioManager().setSelfDeafened(true);
            database.setReadChannel(guild.getIdLong(), voiceChannel.getIdLong(), textChannelId);
            TextChannel chatChannel = guild.getTextChannelById(textChannelId);
            if (chatChannel != null) {
                chatChannel.sendMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(0x00008B))
                        .setDescription(member.getAsMention() + "が参加したため"
                                + voiceChannel.getAsMention() + "に接続しました")
                        .build()).queue();
            }
            if (config.debug) {
                log.debug("{}の自動参加に成功しました", guild.getName());
            }
        } catch (Exception e) {
            log.error("{}のサーバーの自動参加に失敗しました: {}", guild.getName(), e.getMessage());
        }
    }

    private void restoreReadChannels(net.dv8tion.jda.api.JDA jda) {
        if (config.debug) {
            log.debug("読み上げチャンネル一覧取得完了");
        }
        for (Map.Entry<Long, Database.ReadChannel> entry : database.getReadChannels().entrySet()) {
            Guild guild = jda.getGuildById(entry.getKey());
            if (guild == null) {
                database.removeReadChannel(entry.getKey());
                if (config.debug) {
                    log.debug("{}のサーバーが見つかりませんでした", entry.getKey());
                }
                continue;
            }
            VoiceChannel voiceChannel = guild.getVoiceChannelById(entry.getValue().voiceChannelId());
            if (voiceChannel == null) {
                database.removeReadChannel(entry.getKey());
                if (config.debug) {
                    log.debug("{}のボイスチャンネルが見つかりませんでした", guild.getName());
                }
                continue;
            }
            if (countNonBotMembers(voiceChannel) == 0) {
                database.removeReadChannel(entry.getKey());
                if (config.debug) {
                    log.debug("{}のボイスチャンネルのメンバーはいないため読み上げチャンネルから削除しました",
                            guild.getName());
                }
                continue;
            }
            if (!guild.getAudioManager().isConnected()) {
                guild.getAudioManager().openAudioConnection(voiceChannel);
                guild.getAudioManager().setSelfDeafened(true);
                if (config.debug) {
                    log.debug("{}のボイスチャンネルに接続しました", guild.getName());
                }
            }
        }
    }

    private void cleanupDisconnectedChannels() {
        if (jda == null) {
            return;
        }
        try {
            for (Map.Entry<Long, Database.ReadChannel> entry : database.getReadChannels().entrySet()) {
                Guild guild = jda.getGuildById(entry.getKey());
                if (guild == null) {
                    database.removeReadChannel(entry.getKey());
                    if (config.debug) {
                        log.debug("{}のサーバーが見つからないため読み上げチャンネルを削除しました", entry.getKey());
                    }
                    continue;
                }
                VoiceChannel voiceChannel = guild.getVoiceChannelById(entry.getValue().voiceChannelId());
                if (voiceChannel == null) {
                    database.removeReadChannel(entry.getKey());
                    if (config.debug) {
                        log.debug("{}のボイスチャンネルが見つからないため読み上げチャンネルを削除しました",
                                guild.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("非接続チャンネルの削除中にエラーが発生しました", e);
        }
    }

    private static int countNonBotMembers(VoiceChannel channel) {
        return (int) channel.getMembers().stream().filter(member -> !member.getUser().isBot()).count();
    }
}
