import json
import os
import warnings

import aiofiles
import aiosqlite
import asyncmy
import redis
import redis.asyncio as aioredis

import migrate
from config import Config


class Database:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance.pool = None
            cls._instance.connection = None
            cls._instance.config = Config.load_config()
            cls._instance.read_channels_file = "read_channels.json"
        return cls._instance

    async def connect(self) -> None:
        db_config = self.config["database"]
        redis_config = self.config["redis"]
        connection_type = db_config.get("connection", "sqlite").lower()
        self.aioredis = aioredis.Redis(
            host=redis_config["host"],
            port=redis_config["port"],
            db=redis_config["db"],
            password=redis_config["passwd"],
            socket_timeout=5.0,
            socket_connect_timeout=3.0,
        )
        self.use_file_fallback = False

        if connection_type == "sqlite":
            self.connection = await aiosqlite.connect(
                db_config.get("database", "bot.db")
            )
            await migrate.create_tables_sqlite(self)
        elif connection_type in ["mysql", "mariadb"]:
            db_config = {
                "host": db_config["host"],
                "user": db_config["user"],
                "password": db_config["password"],
                "db": db_config["database"],
                "port": db_config["port"],
            }
            self.pool = await asyncmy.create_pool(**db_config)
            await migrate.create_tables_mysql(self)

    async def _load_read_channels(self) -> dict:
        try:
            if os.path.exists(self.read_channels_file):
                async with aiofiles.open(
                    self.read_channels_file, encoding="utf-8"
                ) as f:
                    content = await f.read()
                    return json.loads(content) if content else {}
            return {}
        except (json.JSONDecodeError, FileNotFoundError):
            return {}

    async def _save_read_channels(self, data: dict) -> None:
        async with aiofiles.open(self.read_channels_file, "w", encoding="utf-8") as f:
            await f.write(json.dumps(data, ensure_ascii=False, indent=2))

    async def get_read_channels(self) -> dict[int, tuple[int, int]]:
        if self.use_file_fallback:
            return await self._get_read_channels_file()

        try:
            keys = await self.aioredis.keys("read_channels:*")
            result = {}
            for key in keys:
                server_id = int(key.decode().split(":")[1])
                data = await self.aioredis.hgetall(key)
                if data:
                    voice_channel = int(data[b"voice_channel"].decode())
                    chat_channel = int(data[b"chat_channel"].decode())
                    result[server_id] = (voice_channel, chat_channel)
            return result
        except redis.exceptions.TimeoutError:
            self.use_file_fallback = True
            return await self._get_read_channels_file()

    async def _get_read_channels_file(self) -> dict[int, tuple[int, int]]:
        data = await self._load_read_channels()
        result = {}
        for server_id_str, channels in data.items():
            server_id = int(server_id_str)
            voice_channel = channels["voice_channel"]
            chat_channel = channels["chat_channel"]
            result[server_id] = (voice_channel, chat_channel)
        return result

    async def get_read_channel(self, server_id: int) -> tuple[int, int] | None:
        if self.use_file_fallback:
            return await self._get_read_channel_file(server_id)

        try:
            data = await self.aioredis.hgetall(f"read_channels:{server_id}")
            if data:
                voice_channel = int(data[b"voice_channel"].decode())
                chat_channel = int(data[b"chat_channel"].decode())
                return (voice_channel, chat_channel)
            else:
                return None
        except redis.exceptions.TimeoutError:
            self.use_file_fallback = True
            return await self._get_read_channel_file(server_id)

    async def _get_read_channel_file(self, server_id: int) -> tuple[int, int] | None:
        data = await self._load_read_channels()
        server_id_str = str(server_id)
        if server_id_str in data:
            channels = data[server_id_str]
            voice_channel = channels["voice_channel"]
            chat_channel = channels["chat_channel"]
            return (voice_channel, chat_channel)
        else:
            return None

    async def set_read_channel(
        self, server_id: int, voice_channel: int, chat_channel: int
    ) -> None:
        if self.use_file_fallback:
            await self._set_read_channel_file(server_id, voice_channel, chat_channel)
            return

        try:
            await self.aioredis.hset(
                f"read_channels:{server_id}",
                mapping={"voice_channel": voice_channel, "chat_channel": chat_channel},
            )
        except redis.exceptions.TimeoutError:
            self.use_file_fallback = True
            await self._set_read_channel_file(server_id, voice_channel, chat_channel)

    async def _set_read_channel_file(
        self, server_id: int, voice_channel: int, chat_channel: int
    ) -> None:
        data = await self._load_read_channels()
        server_id_str = str(server_id)
        data[server_id_str] = {
            "voice_channel": voice_channel,
            "chat_channel": chat_channel,
        }
        await self._save_read_channels(data)

    async def remove_read_channel(self, server_id: int) -> None:
        if self.use_file_fallback:
            await self._remove_read_channel_file(server_id)
            return

        try:
            await self.aioredis.delete(f"read_channels:{server_id}")
        except redis.exceptions.TimeoutError:
            self.use_file_fallback = True
            await self._remove_read_channel_file(server_id)

    async def _remove_read_channel_file(self, server_id: int) -> None:
        data = await self._load_read_channels()
        server_id_str = str(server_id)
        if server_id_str in data:
            del data[server_id_str]
            await self._save_read_channels(data)

    async def set_autojoin(
        self, server_id: int, voice_channel: int, text_channel: int
    ) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO autojoin (server_id, voice_channel, text_channel)
                    VALUES (?, ?, ?)
                """,
                    (server_id, voice_channel, text_channel),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO autojoin (server_id, voice_channel, text_channel)
                        VALUES (%s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                        voice_channel = %s,
                        text_channel = %s
                    """,
                    (
                        server_id,
                        voice_channel,
                        text_channel,
                        voice_channel,
                        text_channel,
                    ),
                )
                await conn.commit()

    async def get_autojoin(self, server_id: int) -> tuple[int, int] | None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT voice_channel, text_channel
                    FROM autojoin
                    WHERE server_id = ?
                """,
                    (server_id,),
                )
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT voice_channel, text_channel
                        FROM autojoin
                        WHERE server_id = %s
                    """,
                    (server_id,),
                )
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None

    async def remove_autojoin(self, server_id: int) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    "DELETE FROM autojoin WHERE server_id = ?", (server_id,)
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    "DELETE FROM autojoin WHERE server_id = %s", (server_id,)
                )
                await conn.commit()

    async def set_voice_settings(
        self,
        server_id: int,
        user_id: int,
        engine: str,
        voice_name: str,
        pitch: int,
        speed: float,
        accent: int,
        lmd: int,
    ) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO voice_settings (server_id, user_id, engine, voice_name, pitch, speed, accent, lmd)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    (server_id, user_id, engine, voice_name, pitch, speed, accent, lmd),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO voice_settings (server_id, user_id, engine, voice_name, pitch, speed, accent, lmd)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE
                        engine = %s,
                        voice_name = %s,
                        pitch = %s,
                        speed = %s,
                        accent = %s,
                        lmd = %s
                    """,
                    (
                        server_id,
                        user_id,
                        engine,
                        voice_name,
                        pitch,
                        speed,
                        accent,
                        lmd,
                        engine,
                        voice_name,
                        pitch,
                        speed,
                        accent,
                        lmd,
                    ),
                )
                await conn.commit()

    async def get_voice_settings(
        self, server_id: int, user_id: int
    ) -> tuple[str, str, int, int, int, int] | None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT engine, voice_name, pitch, speed, accent, lmd
                    FROM voice_settings
                    WHERE server_id = ? AND user_id = ?
                """,
                    (server_id, user_id),
                )
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT engine, voice_name, pitch, speed, accent, lmd
                        FROM voice_settings
                        WHERE server_id = %s AND user_id = %s
                    """,
                    (server_id, user_id),
                )
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None

    async def set_dictionary_replacement(
        self, server_id: int, original_text: str, replacement_text: str
    ) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO dictionary_replacements (server_id, original_text, replacement_text)
                    VALUES (?, ?, ?)
                """,
                    (server_id, original_text, replacement_text),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO dictionary_replacements (server_id, original_text, replacement_text)
                        VALUES (%s, %s, %s)
                        ON DUPLICATE KEY UPDATE replacement_text = %s
                    """,
                    (server_id, original_text, replacement_text, replacement_text),
                )
                await conn.commit()

    async def get_dictionary_replacements(self, server_id: int) -> dict[str, str]:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT original_text, replacement_text
                    FROM dictionary_replacements
                    WHERE server_id = ?
                """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return {row[0]: row[1] for row in rows}
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT original_text, replacement_text
                        FROM dictionary_replacements
                        WHERE server_id = %s
                    """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return {row[0]: row[1] for row in rows}

    async def remove_dictionary_replacement(
        self, server_id: int, original_text: str
    ) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    DELETE FROM dictionary_replacements
                    WHERE server_id = ? AND original_text = ?
                """,
                    (server_id, original_text),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        DELETE FROM dictionary_replacements
                        WHERE server_id = %s AND original_text = %s
                    """,
                    (server_id, original_text),
                )
                await conn.commit()

    async def set_global_dictionary_replacement(
        self, original_text: str, replacement_text: str
    ) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO global_dictionary_replacements (original_text, replacement_text)
                    VALUES (?, ?)
                """,
                    (original_text, replacement_text),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO global_dictionary_replacements (original_text, replacement_text)
                        VALUES (%s, %s)
                        ON DUPLICATE KEY UPDATE replacement_text = %s
                    """,
                    (original_text, replacement_text, replacement_text),
                )
                await conn.commit()

    async def get_global_dictionary_replacements(self) -> dict[str, str]:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT original_text, replacement_text
                    FROM global_dictionary_replacements
                """)
                rows = await cursor.fetchall()
                return {row[0]: row[1] for row in rows}
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute("""
                        SELECT original_text, replacement_text
                        FROM global_dictionary_replacements
                    """)
                rows = await cursor.fetchall()
                return {row[0]: row[1] for row in rows}

    # async def remove_global_dictionary_replacement(self, original_text: str) -> None:
    #    if self.config['database']['connection'] == 'sqlite':
    #        async with self.connection.cursor() as cursor:
    #            await cursor.execute("""
    #                DELETE FROM global_dictionary_replacements
    #                WHERE original_text = ?
    #            """, (original_text))
    #            await self.connection.commit()
    #    elif self.config['database']['connection'] in ['mysql', 'mariadb']:
    #        async with self.pool.acquire() as conn:
    #            async with conn.cursor() as cursor:
    #                await cursor.execute("""
    #                    DELETE FROM global_dictionary_replacements
    #                    WHERE original_text = %s
    #                """, (original_text))
    #                await conn.commit()

    async def set_muted_user(self, server_id: int, user_id: int) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO muted_users (server_id, user_id)
                    VALUES (?, ?)
                """,
                    (server_id, user_id),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO muted_users (server_id, user_id)
                        VALUES (%s, %s)
                        ON DUPLICATE KEY UPDATE user_id = %s
                    """,
                    (server_id, user_id, user_id),
                )
                await conn.commit()

    async def remove_muted_user(self, server_id: int, user_id: int) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    DELETE FROM muted_users
                    WHERE server_id = ? AND user_id = ?
                """,
                    (server_id, user_id),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        DELETE FROM muted_users
                        WHERE server_id = %s AND user_id = %s
                    """,
                    (server_id, user_id),
                )
                await conn.commit()

    async def get_muted_users(self, server_id: int) -> list[int]:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT user_id
                    FROM muted_users
                    WHERE server_id = ?
                """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return [row[0] for row in rows]
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT user_id
                        FROM muted_users
                        WHERE server_id = %s
                    """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return [row[0] for row in rows]

    async def is_user_muted(self, server_id: int, user_id: int) -> bool:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT 1
                    FROM muted_users
                    WHERE server_id = ? AND user_id = ?
                """,
                    (server_id, user_id),
                )
                result = await cursor.fetchone()
                return result is not None
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT 1
                        FROM muted_users
                        WHERE server_id = %s AND user_id = %s
                    """,
                    (server_id, user_id),
                )
                result = await cursor.fetchone()
                return result is not None

    async def set_dynamic_join(self, server_id: int, text_channel: int) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT OR REPLACE INTO dynamic_join (server_id, text_channel)
                    VALUES (?, ?)
                """,
                    (server_id, text_channel),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        INSERT INTO dynamic_join (server_id, text_channel)
                        VALUES (%s, %s)
                        ON DUPLICATE KEY UPDATE text_channel = %s
                    """,
                    (server_id, text_channel, text_channel),
                )
                await conn.commit()

    async def get_dynamic_joins(self, server_id: int) -> list[int]:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT text_channel
                    FROM dynamic_join
                    WHERE server_id = ?
                """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return [row[0] for row in rows]
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        SELECT text_channel
                        FROM dynamic_join
                        WHERE server_id = %s
                    """,
                    (server_id,),
                )
                rows = await cursor.fetchall()
                return [row[0] for row in rows]

    async def remove_dynamic_join(self, server_id: int, text_channel: int) -> None:
        if self.config["database"]["connection"] == "sqlite":
            async with self.connection.cursor() as cursor:
                await cursor.execute(
                    """
                    DELETE FROM dynamic_join
                    WHERE server_id = ? AND text_channel = ?
                """,
                    (server_id, text_channel),
                )
                await self.connection.commit()
        elif self.config["database"]["connection"] in ["mysql", "mariadb"]:
            async with self.pool.acquire() as conn, conn.cursor() as cursor:
                await cursor.execute(
                    """
                        DELETE FROM dynamic_join
                        WHERE server_id = %s AND text_channel = %s
                    """,
                    (server_id, text_channel),
                )
                await conn.commit()

    def __del__(self):
        try:
            if hasattr(self, "aioredis"):
                self.aioredis.close()
        except (ImportError, AttributeError):
            # エラーを表示させないためにパスする
            pass
        if self.config["database"]["connection"] == "sqlite":
            if self.connection:
                warnings.simplefilter('ignore')
                self.connection.close()
        elif (
            self.config["database"]["connection"] in ["mysql", "mariadb"] and self.pool
        ):
            self.pool.close()
