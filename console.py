import asyncio

from loguru import logger

from database import Database


async def _shutdown(client):
    db = Database()

    try:
        if getattr(db, "redis", None):
            await db.redis.close()
            logger.info("Redisを閉じました")
    except Exception as e:
        logger.error("Redisの終了エラー:", e)

    try:
        if getattr(db, "connection", None):
            await db.connection.close()
            logger.info("SQLiteを閉じました")
    except Exception as e:
        logger.error("SQLiteの終了エラー:", e)

    try:
        if getattr(db, "pool", None):
            db.pool.close()
            await db.pool.wait_closed()
            logger.info("MySQLプールを閉じました")
    except Exception as e:
        logger.error("MySQLの終了エラー:", e)

    try:
        await client.close()
        logger.info("Discordクライアントを閉じました")
    except Exception as e:
        logger.error("Discordの終了エラー:", e)

def console_listener(client):
    while True:
        try:
            cmd = input().strip()
        except EOFError:
            break

        if cmd == "stop":
            loop = client.loop
            asyncio.run_coroutine_threadsafe(
                _shutdown(client),
                loop
            )
            break
