import aiohttp
import asyncio
import discord
import io
import kanalizer
import re
import romaji_converter
import time
import warnings
from aivisspeech import aivisspeech
from aquestalk import aquestalk1, aquestalk2, aquestalk10
from collections import defaultdict
from config import Config
from database import Database
from functools import lru_cache
from loguru import logger
from text_to_speech import text_to_speech
from voicevox import voicevox

config = Config.load_config()
current_voice_settings = {}
db = Database()
debug = config['debug']
message_queues = defaultdict(asyncio.Queue)
reading_tasks = {}

async def speak_in_voice_channel(voice_client: discord.VoiceClient, engine: str, message: str, voice_name: str, pitch: int, speed: float, accent: int, lmd: int) -> None:
    if not voice_client.is_connected():
        return

    message = romaji_converter.romaji_to_hiragana(message.lower())

    words = re.findall(r'[a-z]+', message.lower())
    for word in words:
        if engine == 'voicevox':
            break
        converted = kana_convert(word)
        message = message.replace(word, converted)

    if engine.startswith('aquestalk'):
        message = text_to_speech().convert(message)

    if debug:
        logger.debug(f"音声合成開始: {message} - 使用する音声合成エンジン: {engine}")
        start_time = time.time()

    try:
        match engine:
            case 'voicevox':
                if not config['engine_enabled']['voicevox']:
                    return
                audio = voicevox(message, int(voice_name), pitch, speed)
            case 'aivisspeech':
                if not config['engine_enabled']['aivisspeech']:
                    return
                audio = aivisspeech(message, int(voice_name), pitch, speed)
            case 'aquestalk1':
                if not config['engine_enabled']['aquestalk1']:
                    return
                audio = aquestalk1(message, voice_name, int(speed))
            case 'aquestalk2':
                if not config['engine_enabled']['aquestalk2']:
                    return
                audio = aquestalk2(message, voice_name, int(speed))
            case 'aquestalk10':
                if not config['engine_enabled']['aquestalk10']:
                    return
                audio = aquestalk10(message, voice_name, int(speed), pitch, accent, lmd)
            case _:
                raise ValueError(f"無効なエンジン: {engine}")

        try:
            audio_data = await audio.get_audio()
        except aiohttp.client_exceptions.ClientConnectorError:
            audio_data = await aquestalk10(text_to_speech().convert(message), 'F1E').get_audio()
        except RuntimeError:
            audio_data = await aquestalk10(text_to_speech().convert(message), 'F1E').get_audio()

        if engine.startswith('aquestalk') and engine != 'aquestalk10':
            audio_data = await pitch_convert(audio_data, pitch)

        if debug:
            end_time = time.time()
            logger.debug(f"音声合成完了 - 所要時間: {end_time - start_time}秒")

        while voice_client.is_playing():
            await asyncio.sleep(0.1)

        process = await asyncio.create_subprocess_exec(
            'ffmpeg', '-i', 'pipe:0', '-f', 's16le',
            '-ar', '48000', '-ac', '2', 'pipe:1',
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )

        stdout, _ = await process.communicate(audio_data)
        voice_client.play(discord.PCMAudio(io.BytesIO(stdout)))
    except Exception as e:
        logger.error(f"音声合成エラー: {e}\n入力メッセージ: {message}")

async def process_message_queue(guild_id: int):
    while True:
        try:
            message_data = await message_queues[guild_id].get()
            if message_data is None:
                break

            message, engine, voice_name, pitch, speed, voice_client, accent, lmd = message_data

            await speak_in_voice_channel(voice_client, engine, message, voice_name, pitch, speed, accent, lmd)
            if debug:
                logger.debug('音声再生が完了しました')

            message_queues[guild_id].task_done()
        except Exception as e:
            logger.error(f"メッセージキュー処理エラー: {e}")
            continue

async def read_message(message: str | discord.Message, guild: discord.Guild = None, author: discord.Member = None, channel: discord.TextChannel = None) -> None:
    if not isinstance(message, str):
        if message.author.bot:
            return

        channels = await db.get_read_channels()
        if message.guild.id not in channels or message.channel.id != channels[message.guild.id][1]:
            return

        guild = message.guild
        author = message.author
        channel = message.channel
        message = message.content.replace('\n', ' ')

    voice_client = guild.voice_client
    if voice_client is None or not voice_client.is_connected():
        return

    message = apply_replacements(message, await db.get_dictionary_replacements(guild.id))
    message = apply_replacements(message, await db.get_global_dictionary_replacements())

    voice_settings = current_voice_settings.get((guild.id, author.id))
    if voice_settings is None and author:
        voice_settings = await db.get_voice_settings(guild.id, author.id)
        if voice_settings:
            current_voice_settings[(guild.id, author.id)] = voice_settings

    engine = 'voicevox'
    voice_name = '2'
    pitch = 100
    speed = 1.0
    accent = None
    lmd = None

    if voice_settings:
        engine, voice_name, pitch, speed, accent, lmd = voice_settings

    for match in re.finditer(r'<@!?(\d+)>', message):
        user_id = int(match.group(1))
        user = guild.get_member(user_id)
        if user is None:
            user = await guild.fetch_member(user_id)
        message = message.replace(match.group(0), user.display_name)

    for match in re.findall(r'<@&(\d+)>', message):
        role_id = int(match)
        role = guild.get_role(role_id)
        if role:
            message = message.replace(f'<@&{match}>', role.name)

    for channel_id_str in re.findall(r'<#(\d+)>', message):
        channel_id = int(channel_id_str)
        channel = guild.get_channel(channel_id)
        if channel:
            cleaned_channel_name = re.sub(r'[\U0001F300-\U0001F64F\U0001F680-\U0001F6FF\u2600-\u26FF\u2700-\u27BF]', '', channel.name)
            message = message.replace(f'<#{channel_id_str}>', cleaned_channel_name)

    message = re.sub(r'https?://(?:[-\w.]|(?:%[\da-fA-F]{2}))+(?:\/[^\s]*)?', 'ユーアールエル省略', message)
    message = re.sub(r'<:[a-zA-Z0-9_]+:[0-9]+>', '', message)

    if len(message) == 0:
        return

    if len(message) >= config['discord']['max_length']:
        message = message[:config['discord']['max_length']] + '。以下省略'

    await message_queues[guild.id].put((message, engine, voice_name, pitch, speed, voice_client, accent, lmd))

    if guild.id not in reading_tasks or reading_tasks[guild.id].done():
        reading_tasks[guild.id] = asyncio.create_task(process_message_queue(guild.id))

def update_voice_settings(guild_id: int, user_id: int, engine: str, voice_name: str, pitch: int, speed: float, accent: int, lmd: int) -> None:
    current_voice_settings[(guild_id, user_id)] = (engine, voice_name, pitch, speed, accent, lmd)

async def pitch_convert(audio_data: bytes, pitch: int) -> bytes:
    process = await asyncio.create_subprocess_exec(
        'ffmpeg', '-i', 'pipe:0',
        '-af', f'asetrate=8000*{pitch}/100,atempo=100/{pitch}',
        '-ar', '8000', '-ac', '1', '-f', 'wav', 'pipe:1',
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
    )
    stdout, _ = await process.communicate(audio_data)
    return stdout

@lru_cache(maxsize=512)
def kana_convert(word: str) -> str:
    with warnings.catch_warnings(action='error', category=kanalizer.IncompleteConversionWarning):
        try:
            return kanalizer.convert(word)
        except kanalizer.IncompleteConversionWarning:
            return word

def apply_replacements(message: str, replacements: dict) -> str:
    for original, replacement in replacements.items():
        message = message.replace(original, replacement)
    return message
