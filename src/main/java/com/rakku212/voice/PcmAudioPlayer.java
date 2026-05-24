package com.rakku212.voice;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public class PcmAudioPlayer implements AudioSendHandler {
    private static final int CHUNK_SIZE = 3840;

    private ByteBuffer buffer;
    private CompletableFuture<Void> finished = new CompletableFuture<>();

    public void load(byte[] pcmData) {
        finished = new CompletableFuture<>();
        buffer = ByteBuffer.wrap(pcmData);
    }

    @Override
    public boolean canProvide() {
        return buffer != null && buffer.hasRemaining();
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        if (buffer == null || !buffer.hasRemaining()) {
            if (!finished.isDone()) {
                finished.complete(null);
            }
            return null;
        }
        byte[] chunk = new byte[CHUNK_SIZE];
        int size = Math.min(CHUNK_SIZE, buffer.remaining());
        buffer.get(chunk, 0, size);
        if (!buffer.hasRemaining() && !finished.isDone()) {
            finished.complete(null);
        }
        return ByteBuffer.wrap(chunk);
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    public CompletableFuture<Void> onFinished() {
        return finished;
    }
}
