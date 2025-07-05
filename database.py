import aiosqlite
import asyncmy
import redis.asyncio as aioredis
from config import Config

class Database:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance.pool = None
            cls._instance.connection = None
            cls._instance.config = Config.load_config()
        return cls._instance

    async def connect(self) -> None:
        db_config = self.config['database']
        redis_config = self.config['redis']
        connection_type = db_config.get('connection', 'sqlite').lower()
        self.aioredis = aioredis.Redis(host=redis_config['host'], port=redis_config['port'], db=redis_config['db'], password=redis_config['passwd'])

        if connection_type == 'sqlite':
            self.connection = await aiosqlite.connect(db_config.get('database', 'bot.db'))
            await self.create_tables_sqlite()
        elif connection_type in ['mysql', 'mariadb']:
            db_config = {
                'host': db_config['host'],
                'user': db_config['user'],
                'password': db_config['password'],
                'db': db_config['database'],
                'port': db_config['port']
            }
            self.pool = await asyncmy.create_pool(**db_config)
            await self.create_tables_mysql()

    async def create_tables_sqlite(self) -> None:
        async with self.connection.cursor() as cursor:
            await cursor.execute("""
                CREATE TABLE IF NOT EXISTS autojoin (
                    server_id INTEGER PRIMARY KEY,
                    voice_channel INTEGER NOT NULL,
                    text_channel INTEGER NOT NULL
                )
            """)

            await cursor.execute("""
                CREATE TABLE IF NOT EXISTS voice_settings (
                    server_id INTEGER,
                    user_id INTEGER,
                    voice_name TEXT NOT NULL,
                    pitch INTEGER NOT NULL,
                    speed INTEGER NOT NULL,
                    engine TEXT NOT NULL,
                    PRIMARY KEY (server_id, user_id)
                )
            """)

            await cursor.execute("""
                CREATE TABLE IF NOT EXISTS dictionary_replacements (
                    server_id INTEGER NOT NULL,
                    original_text TEXT NOT NULL,
                    replacement_text TEXT NOT NULL,
                    PRIMARY KEY (server_id, original_text)
                )
            """)

            await cursor.execute("""
                CREATE TABLE IF NOT EXISTS muted_users (
                    server_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    PRIMARY KEY (server_id, user_id)
                )
            """)

            await cursor.execute("""
                CREATE TABLE IF NOT EXISTS dynamic_join (
                    server_id INTEGER NOT NULL,
                    text_channel INTEGER NOT NULL,
                    PRIMARY KEY (server_id, text_channel)
                )
            """)
            await self.connection.commit()

    async def create_tables_mysql(self) -> None:
        async with self.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                await cursor.execute("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                    AND table_name = 'autojoin'
                """, (self.config['database']['database'],))
                table_exists = await cursor.fetchone()

                if not table_exists[0]:
                    await cursor.execute("""
                        CREATE TABLE autojoin (
                            server_id BIGINT PRIMARY KEY,
                            voice_channel BIGINT NOT NULL,
                            text_channel BIGINT NOT NULL
                        )
                    """)

                await cursor.execute("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                    AND table_name = 'voice_settings'
                """, (self.config['database']['database'],))
                table_exists = await cursor.fetchone()

                if not table_exists[0]:
                    await cursor.execute("""
                        CREATE TABLE voice_settings (
                            server_id BIGINT,
                            user_id BIGINT,
                            voice_name VARCHAR(255) NOT NULL,
                            pitch INTEGER NOT NULL,
                            speed DOUBLE NOT NULL,
                            engine VARCHAR(50) NOT NULL,
                            PRIMARY KEY (server_id, user_id)
                        )
                    """)

                await cursor.execute("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                    AND table_name = 'dictionary_replacements'
                """, (self.config['database']['database'],))
                table_exists = await cursor.fetchone()

                if not table_exists[0]:
                    await cursor.execute("""
                        CREATE TABLE dictionary_replacements (
                            server_id BIGINT NOT NULL,
                            original_text VARCHAR(255) NOT NULL,
                            replacement_text VARCHAR(255) NOT NULL,
                            PRIMARY KEY (server_id, original_text)
                        )
                    """)

                await cursor.execute("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                    AND table_name = 'muted_users'
                """, (self.config['database']['database'],))
                table_exists = await cursor.fetchone()

                if not table_exists[0]:
                    await cursor.execute("""
                        CREATE TABLE muted_users (
                            server_id BIGINT NOT NULL,
                            user_id BIGINT NOT NULL,
                            PRIMARY KEY (server_id, user_id)
                        )
                    """)

                await cursor.execute("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                    AND table_name = 'dynamic_join'
                """, (self.config['database']['database'],))
                table_exists = await cursor.fetchone()

                if not table_exists[0]:
                    await cursor.execute("""
                        CREATE TABLE dynamic_join (
                            server_id BIGINT NOT NULL,
                            text_channel BIGINT NOT NULL,
                            PRIMARY KEY (server_id, text_channel)
                        )
                    """)
                await conn.commit()

    async def get_read_channels(self) -> dict[int, tuple[int, int]]:
        keys = await self.aioredis.keys("read_channels:*")
        result = {}
        for key in keys:
            server_id = int(key.decode().split(":")[1])
            data = await self.aioredis.hgetall(key)
            if data:
                voice_channel = int(data[b'voice_channel'].decode())
                chat_channel = int(data[b'chat_channel'].decode())
                result[server_id] = (voice_channel, chat_channel)
        return result

    async def get_read_channel(self, server_id: int) -> tuple[int, int] | None:
        data = await self.aioredis.hgetall(f"read_channels:{server_id}")
        if data:
            voice_channel = int(data[b'voice_channel'].decode())
            chat_channel = int(data[b'chat_channel'].decode())
            return (voice_channel, chat_channel)
        else:
            return None

    async def set_read_channel(self, server_id: int, voice_channel: int, chat_channel: int) -> None:
        await self.aioredis.hset(f"read_channels:{server_id}", mapping={
            'voice_channel': voice_channel,
            'chat_channel': chat_channel
        })

    async def remove_read_channel(self, server_id: int) -> None:
        await self.aioredis.delete(f"read_channels:{server_id}")

    async def set_autojoin(self, server_id: int, voice_channel: int, text_channel: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    INSERT OR REPLACE INTO autojoin (server_id, voice_channel, text_channel)
                    VALUES (?, ?, ?)
                """, (server_id, voice_channel, text_channel))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        INSERT INTO autojoin (server_id, voice_channel, text_channel)
                        VALUES (%s, %s, %s)
                        ON DUPLICATE KEY UPDATE 
                        voice_channel = %s,
                        text_channel = %s
                    """, (server_id, voice_channel, text_channel, voice_channel, text_channel))
                    await conn.commit()

    async def get_autojoin(self, server_id: int) -> tuple[int, int] | None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT voice_channel, text_channel 
                    FROM autojoin 
                    WHERE server_id = ?
                """, (server_id,))
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT voice_channel, text_channel 
                        FROM autojoin 
                        WHERE server_id = %s
                    """, (server_id,))
                    result = await cursor.fetchone()
                    if result:
                        return result
                    else:
                        return None

    async def remove_autojoin(self, server_id: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("DELETE FROM autojoin WHERE server_id = ?", (server_id,))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("DELETE FROM autojoin WHERE server_id = %s", (server_id,))
                    await conn.commit()

    async def set_voice_settings(self, server_id: int, user_id: int, voice_name: str, pitch: int, speed: int, engine: str) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    INSERT OR REPLACE INTO voice_settings (server_id, user_id, voice_name, pitch, speed, engine)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (server_id, user_id, voice_name, pitch, speed, engine))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        INSERT INTO voice_settings (server_id, user_id, voice_name, pitch, speed, engine)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        ON DUPLICATE KEY UPDATE 
                        voice_name = %s,
                        pitch = %s,
                        speed = %s,
                        engine = %s
                    """, (server_id, user_id, voice_name, pitch, speed, engine, voice_name, pitch, speed, engine))
                    await conn.commit()

    async def get_voice_settings(self, server_id: int, user_id: int) -> tuple[str, int, int, str] | None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT voice_name, pitch, speed, engine 
                    FROM voice_settings 
                    WHERE server_id = ? AND user_id = ?
                """, (server_id, user_id))
                result = await cursor.fetchone()
                if result:
                    return result
                else:
                    return None
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT voice_name, pitch, speed, engine 
                        FROM voice_settings 
                        WHERE server_id = %s AND user_id = %s
                    """, (server_id, user_id))
                    result = await cursor.fetchone()
                    if result:
                        return result
                    else:
                        return None

    async def set_dictionary_replacement(self, server_id: int, original_text: str, replacement_text: str) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    INSERT OR REPLACE INTO dictionary_replacements (server_id, original_text, replacement_text)
                    VALUES (?, ?, ?)
                """, (server_id, original_text, replacement_text))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        INSERT INTO dictionary_replacements (server_id, original_text, replacement_text)
                        VALUES (%s, %s, %s)
                        ON DUPLICATE KEY UPDATE replacement_text = %s
                    """, (server_id, original_text, replacement_text, replacement_text))
                    await conn.commit()

    async def get_dictionary_replacements(self, server_id: int) -> dict[str, str]:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT original_text, replacement_text 
                    FROM dictionary_replacements 
                    WHERE server_id = ?
                """, (server_id,))
                rows = await cursor.fetchall()
                return {row[0]: row[1] for row in rows}
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT original_text, replacement_text 
                        FROM dictionary_replacements 
                        WHERE server_id = %s
                    """, (server_id,))
                    rows = await cursor.fetchall()
                    return {row[0]: row[1] for row in rows}

    async def remove_dictionary_replacement(self, server_id: int, original_text: str) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    DELETE FROM dictionary_replacements 
                    WHERE server_id = ? AND original_text = ?
                """, (server_id, original_text))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        DELETE FROM dictionary_replacements 
                        WHERE server_id = %s AND original_text = %s
                    """, (server_id, original_text))
                    await conn.commit()

    async def set_muted_user(self, server_id: int, user_id: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    INSERT OR REPLACE INTO muted_users (server_id, user_id)
                    VALUES (?, ?)
                """, (server_id, user_id))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        INSERT INTO muted_users (server_id, user_id)
                        VALUES (%s, %s)
                        ON DUPLICATE KEY UPDATE user_id = %s
                    """, (server_id, user_id, user_id))
                    await conn.commit()

    async def remove_muted_user(self, server_id: int, user_id: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    DELETE FROM muted_users 
                    WHERE server_id = ? AND user_id = ?
                """, (server_id, user_id))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        DELETE FROM muted_users 
                        WHERE server_id = %s AND user_id = %s
                    """, (server_id, user_id))
                    await conn.commit()

    async def get_muted_users(self, server_id: int) -> list[int]:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT user_id 
                    FROM muted_users 
                    WHERE server_id = ?
                """, (server_id,))
                rows = await cursor.fetchall()
                return [row[0] for row in rows]
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT user_id 
                        FROM muted_users 
                        WHERE server_id = %s
                    """, (server_id,))
                    rows = await cursor.fetchall()
                    return [row[0] for row in rows]

    async def is_user_muted(self, server_id: int, user_id: int) -> bool:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT 1 
                    FROM muted_users 
                    WHERE server_id = ? AND user_id = ?
                """, (server_id, user_id))
                result = await cursor.fetchone()
                return result is not None
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT 1 
                        FROM muted_users 
                        WHERE server_id = %s AND user_id = %s
                    """, (server_id, user_id))
                    result = await cursor.fetchone()
                    return result is not None

    async def set_dynamic_join(self, server_id: int, text_channel: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    INSERT OR REPLACE INTO dynamic_join (server_id, text_channel)
                    VALUES (?, ?)
                """, (server_id, text_channel))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        INSERT INTO dynamic_join (server_id, text_channel)
                        VALUES (%s, %s)
                        ON DUPLICATE KEY UPDATE text_channel = %s
                    """, (server_id, text_channel, text_channel))
                    await conn.commit()

    async def get_dynamic_joins(self, server_id: int) -> list[int]:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    SELECT text_channel 
                    FROM dynamic_join 
                    WHERE server_id = ?
                """, (server_id,))
                rows = await cursor.fetchall()
                return [row[0] for row in rows]
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        SELECT text_channel 
                        FROM dynamic_join 
                        WHERE server_id = %s
                    """, (server_id,))
                    rows = await cursor.fetchall()
                    return [row[0] for row in rows]

    async def remove_dynamic_join(self, server_id: int, text_channel: int) -> None:
        if self.config['database']['connection'] == 'sqlite':
            async with self.connection.cursor() as cursor:
                await cursor.execute("""
                    DELETE FROM dynamic_join 
                    WHERE server_id = ? AND text_channel = ?
                """, (server_id, text_channel))
                await self.connection.commit()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            async with self.pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute("""
                        DELETE FROM dynamic_join 
                        WHERE server_id = %s AND text_channel = %s
                    """, (server_id, text_channel))
                    await conn.commit()

    def __del__(self):
        try:
            self.aioredis.close()
        except ImportError:
            # エラーを表示させないためにパスする
            pass
        if self.config['database']['connection'] == 'sqlite':
            if self.connection:
                self.connection.close()
        elif self.config['database']['connection'] in ['mysql', 'mariadb']:
            if self.pool:
                self.pool.close()
