async def create_tables_sqlite(db_instance) -> None:
    async with db_instance.connection.cursor() as cursor:
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
                engine TEXT NOT NULL,
                voice_name TEXT NOT NULL,
                pitch INTEGER NOT NULL,
                speed INTEGER NOT NULL,
                accent INTEGER NOT NULL,
                lmd INTEGER NOT NULL,
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
            CREATE TABLE IF NOT EXISTS global_dictionary_replacements (
                original_text TEXT NOT NULL,
                replacement_text TEXT NOT NULL,
                PRIMARY KEY (original_text)
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
        await db_instance.connection.commit()

async def create_tables_mysql(db_instance) -> None:
    async with db_instance.pool.acquire() as conn:
        async with conn.cursor() as cursor:
            await cursor.execute("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = %s
                AND table_name = 'autojoin'
            """, (db_instance.config['database']['database'],))
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
            """, (db_instance.config['database']['database'],))
            table_exists = await cursor.fetchone()

            if not table_exists[0]:
                await cursor.execute("""
                    CREATE TABLE voice_settings (
                        server_id BIGINT,
                        user_id BIGINT,
                        engine VARCHAR(50) NOT NULL,
                        voice_name VARCHAR(255) NOT NULL,
                        pitch INTEGER NOT NULL,
                        speed DOUBLE NOT NULL,
                        accent INTEGER NOT NULL,
                        lmd INTEGER NOT NULL,
                        PRIMARY KEY (server_id, user_id)
                    )
                """)

            await cursor.execute("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = %s
                AND table_name = 'dictionary_replacements'
            """, (db_instance.config['database']['database'],))
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
                AND table_name = 'global_dictionary_replacements'
            """, (db_instance.config['database']['database'],))
            table_exists = await cursor.fetchone()

            if not table_exists[0]:
                await cursor.execute("""
                    CREATE TABLE global_dictionary_replacements (
                        original_text VARCHAR(255) NOT NULL,
                        replacement_text VARCHAR(255) NOT NULL,
                        PRIMARY KEY (original_text)
                    )
                """)

            await cursor.execute("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = %s
                AND table_name = 'muted_users'
            """, (db_instance.config['database']['database'],))
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
            """, (db_instance.config['database']['database'],))
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
