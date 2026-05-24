package com.rakku212.voice.engine;

import com.rakku212.database.Database;

import java.io.IOException;

public interface VoiceEngine {
    byte[] synthesize(String text, Database.VoiceSettings settings) throws IOException, InterruptedException;
}
