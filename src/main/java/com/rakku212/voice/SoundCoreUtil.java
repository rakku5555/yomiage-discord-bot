package com.rakku212.voice;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

public final class SoundCoreUtil {

    static final int SOUND_CORE_OK = 0;
    static final int SOUND_CORE_ERR_INVALID_INPUT = -1;
    static final int SOUND_CORE_ERR_DECODE = -2;
    static final int SOUND_CORE_ERR_ENCODE = -3;
    static final int SOUND_CORE_ERR_PITCH = -4;
    static final int SOUND_CORE_ERR_NULL_PTR = -5;
    static final int SOUND_CORE_ERR_EMPTY = -6;
    static final int SOUND_CORE_ERR_PANIC = -99;

    private static final String VERSION = loadVersion();

    public static String version() {
        return VERSION;
    }

    public static byte[] toDiscordPcm(byte[] audioInput) throws IOException {
        Objects.requireNonNull(audioInput, "audioInput");
        return invoke(
                (input, output) -> SoundCore.INSTANCE.sound_core_to_discord_pcm(input, input.length, output),
                audioInput,
                "Discord PCM 変換"
        );
    }

    public static byte[] pitchConvert(byte[] audioData, int pitch) throws IOException {
        Objects.requireNonNull(audioData, "audioData");
        return invoke(
                (input, output) -> SoundCore.INSTANCE.sound_core_pitch_convert(input, input.length, pitch, output),
                audioData,
                "ピッチ変換"
        );
    }

    private interface SoundCore extends Library {
        SoundCore INSTANCE = load();

        int sound_core_to_discord_pcm(byte[] input, int inputLen, SoundCoreBuffer.ByReference output);

        int sound_core_pitch_convert(byte[] input, int inputLen, int pitch, SoundCoreBuffer.ByReference output);

        void sound_core_buffer_free(SoundCoreBuffer.ByReference buffer);

        Pointer sound_core_version();
    }

    @FunctionalInterface
    private interface NativeCall {
        int invoke(byte[] input, SoundCoreBuffer.ByReference output);
    }

    private static byte[] invoke(NativeCall call, byte[] input, String operation) throws IOException {
        if (input.length == 0) {
            throw new IOException(operation + "に失敗しました: 入力が空です");
        }

        SoundCoreBuffer.ByReference output = new SoundCoreBuffer.ByReference();
        try {
            int code = call.invoke(input, output);
            if (code != SOUND_CORE_OK) {
                throw new IOException(operation + "に失敗しました: " + errorMessage(code));
            }
            output.read();
            if (output.data == null || output.len <= 0) {
                throw new IOException(operation + "に失敗しました: 出力が空です");
            }
            if (output.len > Integer.MAX_VALUE) {
                throw new IOException(operation + "に失敗しました: 出力が大きすぎます");
            }
            return output.data.getByteArray(0, (int) output.len);
        } finally {
            SoundCore.INSTANCE.sound_core_buffer_free(output);
        }
    }

    private static String loadVersion() {
        Pointer versionPointer = SoundCore.INSTANCE.sound_core_version();
        if (versionPointer == null) {
            return "unknown";
        }
        return versionPointer.getString(0);
    }

    private static SoundCore load() {
        NativeLibrary nativeLibrary = resolveNativeLibrary();
        try {
            Path libraryPath = extractLibrary(nativeLibrary.resourcePath(), nativeLibrary.fileName(), "sound_core");
            return Native.load(libraryPath.toAbsolutePath().toString(), SoundCore.class);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "sound-core ネイティブライブラリの読み込みに失敗しました: " + nativeLibrary.fileName(), e
            );
        }
    }

    private static NativeLibrary resolveNativeLibrary() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return new NativeLibrary("/sound_core/sound_core.dll", "sound_core.dll");
        }
        if (osName.contains("linux")) {
            return new NativeLibrary("/sound_core/sound_core.so", "sound_core.so");
        }
        throw new UnsupportedOperationException("sound-core は Windows と Linux のみ対応しています");
    }

    private static Path extractLibrary(String resourcePath, String fileName, String prefix) throws IOException {
        try (InputStream input = SoundCoreUtil.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("リソースが見つかりません: " + resourcePath);
            }
            Path directory = Files.createTempDirectory(prefix + "-");
            Path library = directory.resolve(fileName);
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return library;
        }
    }

    private record NativeLibrary(String resourcePath, String fileName) {
    }

    private static String errorMessage(int code) {
        return switch (code) {
            case SOUND_CORE_ERR_INVALID_INPUT -> "入力が無効です";
            case SOUND_CORE_ERR_DECODE -> "WAVのデコードに失敗しました";
            case SOUND_CORE_ERR_ENCODE -> "WAVのエンコードに失敗しました";
            case SOUND_CORE_ERR_PITCH -> "ピッチ値が範囲外です";
            case SOUND_CORE_ERR_NULL_PTR -> "内部エラー (null pointer)";
            case SOUND_CORE_ERR_EMPTY -> "変換結果が空です";
            case SOUND_CORE_ERR_PANIC -> "内部エラー (panic)";
            default -> "不明なエラー (code: " + code + ")";
        };
    }
}
