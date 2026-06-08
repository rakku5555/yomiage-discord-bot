package com.rakku212.voice;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;

public final class VoiceConnectionHelper {

    public static void disconnect(Guild guild, VoiceChannelService voiceChannelService) {
        voiceChannelService.clearQueue(guild.getIdLong(), guild.getAudioManager());
        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.getSendingHandler() != null) {
            audioManager.setSendingHandler(null);
        }
        if (audioManager.isConnected()) {
            audioManager.closeAudioConnection();
        }
    }
}
