package com.rakku212.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);
    private static final Path CONFIG_PATH = Path.of("config.yaml");
    private static final String DEFAULT_RESOURCE = "/config.yaml";
    private static BotConfig cached;

    public static BotConfig load() {
        if (cached != null) {
            return cached;
        }
        ensureConfigExists();
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            Yaml yaml = new Yaml(new Constructor(BotConfig.class, new LoaderOptions()));
            cached = yaml.load(reader);
            if (cached == null) {
                throw new IllegalStateException("設定ファイルの形式が正しくありません: " + CONFIG_PATH.toAbsolutePath());
            }
            return cached;
        } catch (IOException e) {
            throw new IllegalStateException("設定ファイルの読み込みに失敗しました: " + CONFIG_PATH.toAbsolutePath(), e);
        }
    }

    public static void reload() {
        cached = null;
        load();
    }

    private static void ensureConfigExists() {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            try (InputStream input = Config.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "設定ファイルが見つかりません: " + CONFIG_PATH.toAbsolutePath()
                    );
                }
                Files.copy(input, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("設定ファイルを生成しました: {}", CONFIG_PATH.toAbsolutePath());
            log.info("discord.token を設定してから再起動してください");
        } catch (IOException e) {
            throw new IllegalStateException("設定ファイルの生成に失敗しました: " + CONFIG_PATH.toAbsolutePath(), e);
        }
    }
}
