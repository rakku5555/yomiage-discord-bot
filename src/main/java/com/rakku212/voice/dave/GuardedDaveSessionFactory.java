package com.rakku212.voice.dave;

import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSession;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import org.jetbrains.annotations.NotNull;

public final class GuardedDaveSessionFactory implements DaveSessionFactory {

    private final DaveFactory factory;

    public GuardedDaveSessionFactory(DaveFactory factory) {
        this.factory = factory;
    }

    @Override
    @NotNull
    public DaveSession createDaveSession(@NotNull DaveProtocolCallbacks callbacks, long userId, long channelId) {
        DaveSession session = new LDJDADaveSession(factory, userId, channelId, callbacks);
        return new GuardedDaveSession(session);
    }
}
