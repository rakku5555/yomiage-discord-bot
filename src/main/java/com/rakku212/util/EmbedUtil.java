package com.rakku212.util;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.Color;

public final class EmbedUtil {

    public static MessageCreateData error(String description) {
        return embed(Color.RED, description);
    }

    public static MessageCreateData success(String description) {
        return embed(Color.GREEN, description);
    }

    public static MessageCreateData info(String description) {
        return embed(new Color(0x0099FF), description);
    }

    public static MessageCreateData warning(String description) {
        return embed(Color.ORANGE, description);
    }

    public static MessageCreateData embed(Color color, String description) {
        MessageEmbed embed = new EmbedBuilder()
                .setColor(color)
                .setDescription(description)
                .build();
        return new MessageCreateBuilder().setEmbeds(embed).build();
    }
}
