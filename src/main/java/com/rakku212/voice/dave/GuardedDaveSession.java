package com.rakku212.voice.dave;

import net.dv8tion.jda.api.audio.dave.DaveSession;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a {@link DaveSession} and ignores protocol callbacks after {@link #destroy()},
 * avoiding races where the audio websocket delivers DAVE events after disconnect.
 */
final class GuardedDaveSession implements DaveSession {

    private final DaveSession delegate;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    GuardedDaveSession(DaveSession delegate) {
        this.delegate = delegate;
    }

    private boolean isActive() {
        return !destroyed.get();
    }

    @Override
    public int getMaxProtocolVersion() {
        return delegate.getMaxProtocolVersion();
    }

    @Override
    public int getMaxEncryptedFrameSize(@NotNull MediaType type, int frameSize) {
        if (!isActive()) {
            return frameSize;
        }
        return delegate.getMaxEncryptedFrameSize(type, frameSize);
    }

    @Override
    public int getMaxDecryptedFrameSize(@NotNull MediaType type, long userId, int frameSize) {
        if (!isActive()) {
            return frameSize;
        }
        return delegate.getMaxDecryptedFrameSize(type, userId, frameSize);
    }

    @Override
    public void assignSsrcToCodec(@NotNull Codec codec, int ssrc) {
        if (!isActive()) {
            return;
        }
        delegate.assignSsrcToCodec(codec, ssrc);
    }

    @Override
    public boolean encrypt(@NotNull MediaType mediaType, int ssrc, @NotNull ByteBuffer data,
                           @NotNull ByteBuffer encrypted) {
        if (!isActive()) {
            return false;
        }
        return delegate.encrypt(mediaType, ssrc, data, encrypted);
    }

    @Override
    public boolean decrypt(@NotNull MediaType mediaType, long userId, @NotNull ByteBuffer encrypted,
                           @NotNull ByteBuffer decrypted) {
        if (!isActive()) {
            return false;
        }
        return delegate.decrypt(mediaType, userId, encrypted, decrypted);
    }

    @Override
    public void addUser(long userId) {
        if (!isActive()) {
            return;
        }
        delegate.addUser(userId);
    }

    @Override
    public void removeUser(long userId) {
        if (!isActive()) {
            return;
        }
        delegate.removeUser(userId);
    }

    @Override
    public void initialize() {
        if (!isActive()) {
            return;
        }
        delegate.initialize();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            delegate.destroy();
        }
    }

    @Override
    public void onSelectProtocolAck(int protocolVersion) {
        if (!isActive()) {
            return;
        }
        delegate.onSelectProtocolAck(protocolVersion);
    }

    @Override
    public void onDaveProtocolPrepareTransition(int transitionId, int protocolVersion) {
        if (!isActive()) {
            return;
        }
        delegate.onDaveProtocolPrepareTransition(transitionId, protocolVersion);
    }

    @Override
    public void onDaveProtocolExecuteTransition(int transitionId) {
        if (!isActive()) {
            return;
        }
        delegate.onDaveProtocolExecuteTransition(transitionId);
    }

    @Override
    public void onDaveProtocolPrepareEpoch(long epoch, int protocolVersion) {
        if (!isActive()) {
            return;
        }
        delegate.onDaveProtocolPrepareEpoch(epoch, protocolVersion);
    }

    @Override
    public void onDaveProtocolMLSExternalSenderPackage(@NotNull ByteBuffer externalSenderPackage) {
        if (!isActive()) {
            return;
        }
        delegate.onDaveProtocolMLSExternalSenderPackage(externalSenderPackage);
    }

    @Override
    public void onMLSProposals(@NotNull ByteBuffer proposals) {
        if (!isActive()) {
            return;
        }
        delegate.onMLSProposals(proposals);
    }

    @Override
    public void onMLSPrepareCommitTransition(int transitionId, @NotNull ByteBuffer commit) {
        if (!isActive()) {
            return;
        }
        delegate.onMLSPrepareCommitTransition(transitionId, commit);
    }

    @Override
    public void onMLSWelcome(int transitionId, @NotNull ByteBuffer welcome) {
        if (!isActive()) {
            return;
        }
        delegate.onMLSWelcome(transitionId, welcome);
    }
}
