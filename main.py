import asyncio
import platform
import sys
import threading
from datetime import UTC, datetime, timedelta

import discord
from discord import app_commands
from loguru import logger

from config import Config
from console import console_listener
from discord_cmd import setup_commands
from earthquake import set_discord_client, websocket_client
from vc import db, read_message
from voicevox import voicevox

intents = discord.Intents.default()
intents.message_content = True
intents.voice_states = True
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)
debug = Config.load_config()["debug"]


@client.event
async def on_ready():
    if debug:
        logger.debug("デバッグモードが有効です")
    logger.info(f"{client.user} としてログインしました")

    set_discord_client(client)
    client.loop.create_task(websocket_client())

    await db.connect()
    if debug:
        logger.debug("データベース接続完了")

    setup_commands(tree)
    await tree.sync()
    if debug:
        logger.debug("コマンド同期完了")

    read_channels = await db.get_read_channels()
    if debug:
        logger.debug("読み上げチャンネル一覧取得完了")
    for guild_id, (voice_channel_id, _) in read_channels.items():
        guild = client.get_guild(guild_id)
        if not guild:
            await db.remove_read_channel(guild_id)
            if debug:
                logger.debug(f"{guild_id}のサーバーが見つかりませんでした")
            continue
        voice_channel = guild.get_channel(voice_channel_id)
        if not voice_channel:
            await db.remove_read_channel(guild_id)
            if debug:
                logger.debug(f"{guild.name}のボイスチャンネルが見つかりませんでした")
            continue
        member_count = len([m for m in voice_channel.members if not m.bot])
        if member_count == 0:
            await db.remove_read_channel(guild_id)
            if debug:
                logger.debug(
                    f"{guild.name}のボイスチャンネルのメンバーはいないため読み上げチャンネルから削除しました"
                )
            continue
        if not guild.voice_client or not guild.voice_client.is_connected():
            await voice_channel.connect(self_deaf=True)
            if debug:
                logger.debug(f"{guild.name}のボイスチャンネルに接続しました")

    try:
        config = await Config.async_load_config()
        if (
            config["engine_enabled"]["voicevox"]
            and config["voicevox"]["edition"]["core"]
        ):
            await voicevox.init()
    except Exception as e:
        logger.error(f"voicevoxの初期化に失敗しました: {e}")

    client.loop.create_task(cleanup_disconnected_channels())


@client.event
async def on_voice_state_update(
    member: discord.Member, before: discord.VoiceState, after: discord.VoiceState
):
    if member.id == client.user.id:
        if (
            before.channel is not None
            and after.channel is not None
            and before.channel.id != after.channel.id
        ):
            read_channel = await db.get_read_channel(member.guild.id)
            if read_channel:
                await db.set_read_channel(
                    member.guild.id, after.channel.id, read_channel[1]
                )
                if debug:
                    logger.debug(
                        f"{member.guild.name}の読み上げチャンネルをデータベースから更新しました"
                    )
        return

    if before.channel is None and after.channel is not None:
        autojoin = await db.get_autojoin(member.guild.id)
        if autojoin and after.channel.id == autojoin[0]:
            member_count = len([m for m in after.channel.members if not m.bot])
            if member_count == 1 and member.guild.voice_client is None:
                await connect_to_voice_channel(
                    member.guild, after.channel, autojoin[1], debug, member
                )

        dynamic_joins = await db.get_dynamic_joins(member.guild.id)
        if dynamic_joins:
            for text_channel_id in dynamic_joins:
                text_channel = member.guild.get_channel(text_channel_id)
                if text_channel.category_id != after.channel.category_id:
                    continue

                non_bot_members = [m for m in after.channel.members if not m.bot]
                if not non_bot_members or member.guild.voice_client is not None:
                    break

                await asyncio.sleep(1)
                channel = member.guild.get_channel(after.channel.id)
                if not channel:
                    break

                non_bot_members = [m for m in channel.members if not m.bot]
                if (
                    non_bot_members
                    and member.guild.voice_client is None
                    and datetime.now(UTC) - after.channel.created_at
                    <= timedelta(minutes=5)
                ):
                    await connect_to_voice_channel(
                        member.guild, after.channel, text_channel_id, debug, member
                    )
                break

    voice_client = member.guild.voice_client
    if voice_client is None:
        return

    channel = voice_client.channel
    member_count = len([m for m in channel.members if not m.bot])

    if voice_client.is_connected():
        if (
            before.channel is None
            and voice_client.channel == after.channel
            and member_count > 1
        ):
            await read_message(
                f"{member.display_name}が参加しました",
                member.guild,
                member,
                after.channel,
            )
            if debug:
                logger.debug(
                    f"{member.guild.name}に{member.display_name}が参加しました"
                )
        elif (
            voice_client.channel == before.channel
            and after.channel is None
            and member_count >= 1
        ):
            await read_message(
                f"{member.display_name}が退出しました",
                member.guild,
                member,
                before.channel,
            )
            if debug:
                logger.debug(
                    f"{member.guild.name}に{member.display_name}が退出しました"
                )

    if member_count > 0:
        return

    read_channel = await db.get_read_channel(voice_client.guild.id)
    if not read_channel:
        if voice_client.is_connected():
            await voice_client.disconnect()
        return

    _, chat_channel_id = read_channel
    await voice_client.disconnect()

    if chat_channel_id:
        chat_channel = voice_client.guild.get_channel(chat_channel_id)
        if chat_channel:
            await chat_channel.send(
                embed=discord.Embed(
                    color=discord.Color.dark_blue(),
                    description=f"{voice_client.channel.mention}からユーザーがいなくなったため退出しました",
                )
            )

    await db.remove_read_channel(voice_client.guild.id)
    if debug:
        logger.debug(
            f"{voice_client.guild.name}のボイスチャンネルのメンバーはいないため読み上げチャンネルから切断しました"
        )


@client.event
async def on_message(message: discord.Message):
    if message.author.bot:
        return

    if message.guild is None:
        await message.channel.send(
            embed=discord.Embed(
                color=discord.Color.red(),
                description="このBOTはここでは使用できません。",
            )
        )
        return

    if await db.is_user_muted(message.guild.id, message.author.id):
        return
    await read_message(message)


if platform.system().lower() not in ["windows", "linux"]:
    logger.critical("サポートされていないオペレーティングシステムです")
    sys.exit(1)


async def connect_to_voice_channel(
    guild: discord.Guild,
    voice_channel: discord.VoiceChannel,
    text_channel_id: int,
    debug: bool,
    member: discord.Member,
) -> None:
    try:
        await asyncio.wait_for(voice_channel.connect(self_deaf=True), timeout=30.0)
        await db.set_read_channel(guild.id, voice_channel.id, text_channel_id)
        chat_channel = guild.get_channel(text_channel_id)
        if chat_channel:
            await chat_channel.send(
                embed=discord.Embed(
                    color=discord.Color.dark_blue(),
                    description=f"{member.mention}が参加したため{voice_channel.mention}に接続しました",
                )
            )
        if debug:
            logger.debug(f"{guild.name}の自動参加に成功しました")
    except Exception as e:
        logger.error(f"{guild.name}のサーバーの自動参加に失敗しました: {str(e)}")


async def cleanup_disconnected_channels() -> None:
    while True:
        try:
            read_channels = await db.get_read_channels()
            for guild_id, (voice_channel_id, _) in read_channels.items():
                guild = client.get_guild(guild_id)
                if not guild:
                    await db.remove_read_channel(guild_id)
                    if debug:
                        logger.debug(
                            f"{guild_id}のサーバーが見つからないため読み上げチャンネルを削除しました"
                        )
                    continue

                voice_channel = guild.get_channel(voice_channel_id)
                if not voice_channel:
                    await db.remove_read_channel(guild_id)
                    if debug:
                        logger.debug(
                            f"{guild.name}のボイスチャンネルが見つからないため読み上げチャンネルを削除しました"
                        )
                    continue

        except Exception as e:
            logger.error(f"非接続チャンネルの削除中にエラーが発生しました: {e}")

        await asyncio.sleep(300)

threading.Thread(
    target=console_listener,
    name="console",
    args=(client,),
    daemon=True
).start()

client.run(Config.load_config()["discord"]["token"])
