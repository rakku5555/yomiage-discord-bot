package com.rakku212.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class Migration {

    static void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS autojoin (
                        server_id INTEGER PRIMARY KEY,
                        voice_channel INTEGER NOT NULL,
                        text_channel INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS voice_settings (
                        server_id INTEGER,
                        user_id INTEGER,
                        engine TEXT NOT NULL,
                        voice_name TEXT NOT NULL,
                        pitch INTEGER NOT NULL,
                        speed REAL NOT NULL,
                        accent INTEGER NOT NULL,
                        lmd INTEGER NOT NULL,
                        PRIMARY KEY (server_id, user_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dictionary_replacements (
                        server_id INTEGER NOT NULL,
                        original_text TEXT NOT NULL,
                        replacement_text TEXT NOT NULL,
                        PRIMARY KEY (server_id, original_text)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS global_dictionary_replacements (
                        original_text TEXT NOT NULL,
                        replacement_text TEXT NOT NULL,
                        PRIMARY KEY (original_text)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS muted_users (
                        server_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        PRIMARY KEY (server_id, user_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS dynamic_join (
                        server_id INTEGER NOT NULL,
                        text_channel INTEGER NOT NULL,
                        PRIMARY KEY (server_id, text_channel)
                    )
                    """);
        }
    }
}
