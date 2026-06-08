package com.rakku212.database;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rakku212.config.BotConfig;
import com.rakku212.config.Config;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Database {
    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final Gson GSON = new Gson();
    private static final Type READ_CHANNELS_TYPE = new TypeToken<Map<String, ReadChannelEntry>>() {}.getType();

    private final BotConfig config;
    private Connection connection;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private String readChannelsKeyPrefix = "read_channels:";
    private boolean useFileFallback;
    private final Path readChannelsFile = Path.of("read_channels.json");

    public Database() {
        this.config = Config.load();
    }

    public synchronized void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        String path = config.database.path != null ? config.database.path : "bot.db";
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        Migration.createTables(connection);

        readChannelsKeyPrefix = normalizeRedisKeyPrefix(config.redis.key_prefix);

        try {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(config.redis.host)
                    .withPort(config.redis.port)
                    .withDatabase(config.redis.db);
            if (config.redis.passwd != null && !config.redis.passwd.isBlank()) {
                builder.withPassword(config.redis.passwd.toCharArray());
            }
            redisClient = RedisClient.create(builder.build());
            redisConnection = redisClient.connect();
            useFileFallback = false;
        } catch (Exception e) {
            log.error("Redisへの接続に失敗したため、ファイルフォールバックを使用します", e);
            useFileFallback = true;
        }
    }

    public synchronized void close() {
        try {
            if (redisConnection != null) {
                redisConnection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown();
            }
        } catch (Exception e) {
            log.error("Redisの終了エラー", e);
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("SQLiteの終了エラー", e);
        }
    }

    public Map<Long, ReadChannel> getReadChannels() {
        if (useFileFallback) {
            return getReadChannelsFromFile();
        }
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            List<String> keys = commands.keys(readChannelsKeyPrefix + "*");
            Map<Long, ReadChannel> result = new HashMap<>();
            for (String key : keys) {
                long serverId = Long.parseLong(key.substring(readChannelsKeyPrefix.length()));
                Map<String, String> data = commands.hgetall(key);
                if (data != null && !data.isEmpty()) {
                    result.put(serverId, new ReadChannel(
                            Long.parseLong(data.get("voice_channel")),
                            Long.parseLong(data.get("chat_channel"))
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Redis読み取り失敗、ファイルフォールバックに切り替え", e);
            useFileFallback = true;
            return getReadChannelsFromFile();
        }
    }

    public Optional<ReadChannel> getReadChannel(long serverId) {
        if (useFileFallback) {
            return Optional.ofNullable(getReadChannelsFromFile().get(serverId));
        }
        try {
            Map<String, String> data = redisConnection.sync().hgetall(readChannelsKeyPrefix + serverId);
            if (data == null || data.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ReadChannel(
                    Long.parseLong(data.get("voice_channel")),
                    Long.parseLong(data.get("chat_channel"))
            ));
        } catch (Exception e) {
            log.error("Redis読み取り失敗 (serverId={})、ファイルフォールバックに切り替え", serverId, e);
            useFileFallback = true;
            return Optional.ofNullable(getReadChannelsFromFile().get(serverId));
        }
    }

    public void setReadChannel(long serverId, long voiceChannel, long chatChannel) {
        if (useFileFallback) {
            setReadChannelToFile(serverId, voiceChannel, chatChannel);
            return;
        }
        try {
            redisConnection.sync().hset(readChannelsKeyPrefix + serverId, Map.of(
                    "voice_channel", String.valueOf(voiceChannel),
                    "chat_channel", String.valueOf(chatChannel)
            ));
        } catch (Exception e) {
            log.error("Redis書き込み失敗 (serverId={})、ファイルフォールバックに切り替え", serverId, e);
            useFileFallback = true;
            setReadChannelToFile(serverId, voiceChannel, chatChannel);
        }
    }

    public void removeReadChannel(long serverId) {
        if (useFileFallback) {
            removeReadChannelFromFile(serverId);
            return;
        }
        try {
            redisConnection.sync().del(readChannelsKeyPrefix + serverId);
        } catch (Exception e) {
            log.error("Redis削除失敗 (serverId={})、ファイルフォールバックに切り替え", serverId, e);
            useFileFallback = true;
            removeReadChannelFromFile(serverId);
        }
    }

    public void setAutojoin(long serverId, long voiceChannel, long textChannel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO autojoin (server_id, voice_channel, text_channel)
                VALUES (?, ?, ?)
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, voiceChannel);
            ps.setLong(3, textChannel);
            ps.executeUpdate();
        }
    }

    public Optional<ReadChannel> getAutojoin(long serverId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT voice_channel, text_channel FROM autojoin WHERE server_id = ?
                """)) {
            ps.setLong(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ReadChannel(rs.getLong(1), rs.getLong(2)));
                }
            }
        }
        return Optional.empty();
    }

    public void removeAutojoin(long serverId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM autojoin WHERE server_id = ?")) {
            ps.setLong(1, serverId);
            ps.executeUpdate();
        }
    }

    public void setVoiceSettings(long serverId, long userId, String engine, String voiceName,
                                 int pitch, double speed, int accent, int lmd) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO voice_settings
                (server_id, user_id, engine, voice_name, pitch, speed, accent, lmd)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            ps.setString(3, engine);
            ps.setString(4, voiceName);
            ps.setInt(5, pitch);
            ps.setDouble(6, speed);
            ps.setInt(7, accent);
            ps.setInt(8, lmd);
            ps.executeUpdate();
        }
    }

    public Optional<VoiceSettings> getVoiceSettings(long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT engine, voice_name, pitch, speed, accent, lmd
                FROM voice_settings WHERE server_id = ? AND user_id = ?
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new VoiceSettings(
                            rs.getString("engine"),
                            rs.getString("voice_name"),
                            rs.getInt("pitch"),
                            rs.getDouble("speed"),
                            rs.getInt("accent"),
                            rs.getInt("lmd")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void setDictionaryReplacement(long serverId, String original, String replacement) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO dictionary_replacements (server_id, original_text, replacement_text)
                VALUES (?, ?, ?)
                """)) {
            ps.setLong(1, serverId);
            ps.setString(2, original);
            ps.setString(3, replacement);
            ps.executeUpdate();
        }
    }

    public Map<String, String> getDictionaryReplacements(long serverId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT original_text, replacement_text FROM dictionary_replacements WHERE server_id = ?
                """)) {
            ps.setLong(1, serverId);
            return readStringMap(ps);
        }
    }

    public void removeDictionaryReplacement(long serverId, String original) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM dictionary_replacements WHERE server_id = ? AND original_text = ?
                """)) {
            ps.setLong(1, serverId);
            ps.setString(2, original);
            ps.executeUpdate();
        }
    }

    public void setGlobalDictionaryReplacement(String original, String replacement) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO global_dictionary_replacements (original_text, replacement_text)
                VALUES (?, ?)
                """)) {
            ps.setString(1, original);
            ps.setString(2, replacement);
            ps.executeUpdate();
        }
    }

    public Map<String, String> getGlobalDictionaryReplacements() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT original_text, replacement_text FROM global_dictionary_replacements
                """)) {
            return readStringMap(ps);
        }
    }

    public void setMutedUser(long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO muted_users (server_id, user_id) VALUES (?, ?)
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void removeMutedUser(long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM muted_users WHERE server_id = ? AND user_id = ?
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public List<Long> getMutedUsers(long serverId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT user_id FROM muted_users WHERE server_id = ?
                """)) {
            ps.setLong(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(rs.getLong(1));
                }
                return users;
            }
        }
    }

    public boolean isUserMuted(long serverId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM muted_users WHERE server_id = ? AND user_id = ?
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void setDynamicJoin(long serverId, long textChannel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO dynamic_join (server_id, text_channel) VALUES (?, ?)
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, textChannel);
            ps.executeUpdate();
        }
    }

    public List<Long> getDynamicJoins(long serverId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT text_channel FROM dynamic_join WHERE server_id = ?
                """)) {
            ps.setLong(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> channels = new ArrayList<>();
                while (rs.next()) {
                    channels.add(rs.getLong(1));
                }
                return channels;
            }
        }
    }

    public void removeDynamicJoin(long serverId, long textChannel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM dynamic_join WHERE server_id = ? AND text_channel = ?
                """)) {
            ps.setLong(1, serverId);
            ps.setLong(2, textChannel);
            ps.executeUpdate();
        }
    }

    private static String normalizeRedisKeyPrefix(String keyPrefix) {
        String prefix = keyPrefix;
        if (prefix == null || prefix.isBlank()) {
            prefix = "read_channels";
        }
        prefix = prefix.strip();
        while (prefix.endsWith(":")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + ":";
    }

    private Map<String, String> readStringMap(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            Map<String, String> map = new HashMap<>();
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
            return map;
        }
    }

    private Map<Long, ReadChannel> getReadChannelsFromFile() {
        Map<String, ReadChannelEntry> raw = loadReadChannelsFile();
        Map<Long, ReadChannel> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(Long.parseLong(key), new ReadChannel(value.voice_channel, value.chat_channel)));
        return result;
    }

    private void setReadChannelToFile(long serverId, long voiceChannel, long chatChannel) {
        Map<String, ReadChannelEntry> data = loadReadChannelsFile();
        data.put(String.valueOf(serverId), new ReadChannelEntry(voiceChannel, chatChannel));
        saveReadChannelsFile(data);
    }

    private void removeReadChannelFromFile(long serverId) {
        Map<String, ReadChannelEntry> data = loadReadChannelsFile();
        data.remove(String.valueOf(serverId));
        saveReadChannelsFile(data);
    }

    private Map<String, ReadChannelEntry> loadReadChannelsFile() {
        if (!Files.exists(readChannelsFile)) {
            return new HashMap<>();
        }
        try {
            String content = Files.readString(readChannelsFile);
            if (content.isBlank()) {
                return new HashMap<>();
            }
            Map<String, ReadChannelEntry> data = GSON.fromJson(content, READ_CHANNELS_TYPE);
            return data != null ? data : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private void saveReadChannelsFile(Map<String, ReadChannelEntry> data) {
        try {
            Files.writeString(readChannelsFile, GSON.toJson(data));
        } catch (IOException e) {
            log.error("read_channels.jsonの保存に失敗しました", e);
        }
    }

    public record ReadChannel(long voiceChannelId, long chatChannelId) {
    }

    public record VoiceSettings(String engine, String voiceName, int pitch, double speed, int accent, int lmd) {
    }

    private record ReadChannelEntry(long voice_channel, long chat_channel) {
    }
}
