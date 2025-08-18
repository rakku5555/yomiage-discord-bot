import discord
import jaconv
import json
import math
import random
from config import Config
from database import Database
from discord import app_commands
from discord.ui import View, Button
from loguru import logger
from vc import update_voice_settings, message_queues, reading_tasks

db = Database()
engine_key = {
    'aquestalk1': 'AquesTalk1',
    'aquestalk2': 'AquesTalk2',
    'aquestalk10': 'AquesTalk10',
    'voicevox': 'voicevox',
    'aivisspeech': 'aivisspeech'
}

async def ensure_db_connection():
    if db.pool is None:
        await db.connect()

def setup_commands(tree: app_commands.CommandTree):
    @tree.command(name='join', description='ボイスチャンネルに参加')
    async def join(interaction: discord.Interaction):
        await ensure_db_connection()

        if interaction.user.voice is None:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='ボイスチャンネルに接続していません。'), ephemeral=True)
            return

        voice_channel = interaction.user.voice.channel
        try:
            await voice_channel.connect(self_deaf=True)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f"{voice_channel.mention}に参加しました！このチャンネルのメッセージを読み上げます。"))

            await db.set_read_channel(interaction.guild_id, voice_channel.id, interaction.channel_id)
        except discord.ClientException:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"すでに{voice_channel.mention}に接続しています。"), ephemeral=True)

    @tree.command(name='leave', description='ボイスチャンネルから退出')
    async def leave(interaction: discord.Interaction):
        await ensure_db_connection()

        if interaction.guild.voice_client is None:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='ボイスチャンネルに接続していません。'), ephemeral=True)
            return

        try:
            if interaction.guild_id in message_queues:
                await message_queues[interaction.guild_id].put(None)
                if interaction.guild_id in reading_tasks and not reading_tasks[interaction.guild_id].done():
                    await reading_tasks[interaction.guild_id]
                if interaction.guild_id in reading_tasks:
                    del reading_tasks[interaction.guild_id]
                del message_queues[interaction.guild_id]
            await interaction.guild.voice_client.disconnect()
            await db.remove_read_channel(interaction.guild_id)

            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description='ボイスチャンネルから退出しました！'))
        except discord.ClientException:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='ボイスチャンネルから退出できませんでした。'), ephemeral=True)

    autojoin_group = app_commands.Group(name='autojoin', description='自動参加機能の設定')

    @autojoin_group.command(name='add', description='自動参加するボイスチャンネルと読み上げるテキストチャンネルを設定します')
    @app_commands.describe(voice='参加するボイスチャンネル', text='読み上げるテキストチャンネル')
    async def autojoin_add(interaction: discord.Interaction, voice: discord.VoiceChannel, text: discord.TextChannel):
        await ensure_db_connection()

        try:
            await db.set_autojoin(interaction.guild_id, voice.id, text.id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f"自動参加設定を追加しました。\n"f"ボイスチャンネル: {voice.mention}\n"f"テキストチャンネル: {text.mention}"))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"設定の更新に失敗しました: {str(e)}"))

    @autojoin_group.command(name='remove', description='自動参加設定を削除します')
    @app_commands.describe(voice='削除するボイスチャンネル')
    async def autojoin_remove(interaction: discord.Interaction, voice: discord.VoiceChannel):
        await ensure_db_connection()

        try:
            autojoin = await db.get_autojoin(interaction.guild_id)
            if not autojoin:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='自動参加設定はありません。'), ephemeral=True)
                return

            if voice.id != autojoin[0]:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'指定されたボイスチャンネル {voice.mention} の自動参加設定はありません。'), ephemeral=True)
                return

            await db.remove_autojoin(interaction.guild_id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f'ボイスチャンネル {voice.mention} の自動参加設定を削除しました。'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"設定の削除に失敗しました: {str(e)}"))

    @autojoin_group.command(name='list', description='現在の自動参加設定一覧を表示します')
    async def autojoin_list(interaction: discord.Interaction):
        await ensure_db_connection()

        autojoin = await db.get_autojoin(interaction.guild_id)
        if not autojoin:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='自動参加設定はありません。'), ephemeral=True)
            return

        voice_channel = interaction.guild.get_channel(autojoin[0])
        text_channel = interaction.guild.get_channel(autojoin[1])

        if not voice_channel or not text_channel:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='設定されたチャンネルが見つかりません。'), ephemeral=True)
            return

        await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f"現在の自動参加設定:\nボイスチャンネル: {voice_channel.name}\nテキストチャンネル: {text_channel.name}"))

    tree.add_command(autojoin_group)

    try:
        with open('voice_character.json', encoding='utf-8') as f:
            voice_characters = json.load(f)
    except Exception as e:
        logger.error(f"音声キャラクターの設定を読み込めませんでした: {str(e)}")
        voice_characters = []

    @tree.command(name='setvoice', description='ボイスキャラクターと読み上げ速度を設定します')
    @app_commands.describe(
        engine='音声エンジンの選択',
        voice='声の指定',
        pitch='声の高さ (デフォルトは100)',
        speed='読み上げ速度 (AquesTalk: 50-200, VOICEVOX/AivisSpeech: 0.5-5)',
        accent='アクセントの強さ (AquesTalk10のみ デフォルト: 100)',
        lmd='声質の高低 (AquesTalk10のみ デフォルト: 100)'
    )
    @app_commands.choices(
        engine=[
            app_commands.Choice(name='AquesTalk1', value='aquestalk1'),
            app_commands.Choice(name='AquesTalk2', value='aquestalk2'),
            app_commands.Choice(name='AquesTalk10', value='aquestalk10'),
            app_commands.Choice(name='VOICEVOX', value='voicevox'),
            app_commands.Choice(name='AivisSpeech', value='aivisspeech')
        ]
    )
    async def setvoice(interaction: discord.Interaction, engine: str, voice: str, pitch: int = 100, speed: float = 1.0, accent: int = 100, lmd: int = 100):
        await ensure_db_connection()

        if engine.startswith('aquestalk'):
            if speed == 1.0:
                speed = 100
            if speed < 50 or speed > 200:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='AquesTalkの速度は50から200の間で指定してください。'), ephemeral=True)
                return
        else:
            if speed < 0.5 or speed > 5:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='VOICEVOX/AivisSpeechの速度は0.5から10の間で指定してください。'), ephemeral=True)
                return

        config = await Config.async_load_config()

        match engine:
            case 'aquestalk1':
                is_valid, error_message = validate_voice_engine(engine, voice, config, voice_characters)
                if not is_valid:
                    await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=error_message), ephemeral=True)
                    return
            case 'aquestalk2':
                is_valid, error_message = validate_voice_engine(engine, voice, config, voice_characters)
                if not is_valid:
                    await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=error_message), ephemeral=True)
                    return
            case 'aquestalk10':
                is_valid, error_message = validate_voice_engine(engine, voice, config, voice_characters)
                if not is_valid:
                    await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=error_message), ephemeral=True)
                    return
            case 'voicevox':
                is_valid, error_message = validate_voice_engine(engine, voice, config, voice_characters)
                if not is_valid:
                    await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=error_message), ephemeral=True)
                    return
            case 'aivisspeech':
                is_valid, error_message = validate_voice_engine(engine, voice, config, voice_characters)
                if not is_valid:
                    await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=error_message), ephemeral=True)
                    return

        try:
            await db.set_voice_settings(interaction.guild_id, interaction.user.id, engine, voice, pitch, speed, accent, lmd)
            update_voice_settings(interaction.guild_id, interaction.user.id, engine, voice, pitch, speed, accent, lmd)

            voice_name = get_voice_name(engine, voice, voice_characters)

            message = (
                f"ボイス設定を更新しました。\n"
                f"エンジン: {engine}\n"
                f"キャラクター: {voice_name}\n"
                f"声の高さ: {pitch}\n"
                f"速度: {int(speed) if float(speed).is_integer() else speed}\n"
            )
            if engine == 'voicevox':
                message += f"VOICEVOX: {voice_name}"
            if engine == 'aquestalk10':
                message += f"アクセントの強さ: {accent}\n声質の高低: {lmd}"

            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.blue(), description=message))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"設定の更新に失敗しました: {str(e)}"))

    @setvoice.autocomplete('voice')
    async def voice_autocomplete(interaction: discord.Interaction, current: str,) -> list[app_commands.Choice[str]]:
        engine = interaction.namespace.engine
        if not engine:
            return []

        voices = voice_characters[engine_key[engine]]
        choices = [
            app_commands.Choice(name=voice['name'], value=voice['value'])
            for voice in voices
            if current.lower() in voice['name'].lower()
        ]
        if len(choices) > 25:
            choices = random.sample(choices, 25)
        return choices

    @tree.command(name='skip', description='現在の読み上げをすべてスキップします')
    async def skip(interaction: discord.Interaction):
        if interaction.guild.voice_client is None:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='ボイスチャンネルに接続していません。'), ephemeral=True)
            return

        voice_client = interaction.guild.voice_client
        if not voice_client.is_playing():
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='現在読み上げ中の音声はありません。'), ephemeral=True)
            return

        voice_client.stop()
        await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description='読み上げを停止しました。'))

    dict_group = app_commands.Group(name='dict', description='辞書機能の設定')

    @dict_group.command(name='add', description='単語の読み方を登録します')
    @app_commands.describe(word='登録する単語', to='変換後の読み方')
    async def dict_add(interaction: discord.Interaction, word: str, to: str):
        word = word.lower()
        to = jaconv.hira2kata(to)
        await ensure_db_connection()

        try:
            await db.set_dictionary_replacement(interaction.guild_id, word, to)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.blue(), description=f"単語を登録しました。\n「{word}」→「{to}」"))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語の登録に失敗しました: {str(e)}"))

    @dict_group.command(name='list', description='登録されている単語の一覧を表示します')
    async def dict_list(interaction: discord.Interaction):
        await ensure_db_connection()

        try:
            replacements = await db.get_dictionary_replacements(interaction.guild_id)

            if not replacements:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='登録されている単語はありません。'), ephemeral=True)
                return

            embed, view = create_dict_pagination_response(replacements, '登録されている単語一覧')
            await interaction.response.send_message(embed=embed, view=view)
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語一覧の取得に失敗しました: {str(e)}"))

    @dict_group.command(name='remove', description='登録されている単語を削除します')
    @app_commands.describe(word='削除する単語')
    async def dict_remove(interaction: discord.Interaction, word: str):
        await ensure_db_connection()

        try:
            replacements = await db.get_dictionary_replacements(interaction.guild_id)
            if word not in replacements:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語「{word}」は登録されていません。"), ephemeral=True)
                return

            await db.remove_dictionary_replacement(interaction.guild_id, word)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.purple(), description=f"単語「{word}」を削除しました。"))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語の削除に失敗しました: {str(e)}"))

    tree.add_command(dict_group)

    global_dict_group = app_commands.Group(name='global_dict', description='グローバル辞書機能の設定')

    @global_dict_group.command(name='add', description='グローバル単語の読み方を登録します')
    @app_commands.describe(word='登録する単語', to='変換後の読み方')
    async def dict_add(interaction: discord.Interaction, word: str, to: str):
        word = word.lower()
        to = jaconv.hira2kata(to)
        await ensure_db_connection()

        try:
            await db.set_global_dictionary_replacement(word, to)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.blue(), description=f"単語を登録しました。\n「{word}」→「{to}」"))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語の登録に失敗しました: {str(e)}"))

    @global_dict_group.command(name='list', description='グローバル登録されている単語の一覧を表示します')
    async def dict_list(interaction: discord.Interaction):
        await ensure_db_connection()

        try:
            replacements = await db.get_global_dictionary_replacements()

            if not replacements:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='登録されている単語はありません。'), ephemeral=True)
                return

            embed, view = create_dict_pagination_response(replacements, 'グローバル辞書一覧')
            await interaction.response.send_message(embed=embed, view=view)
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f"単語一覧の取得に失敗しました: {str(e)}"))

    tree.add_command(global_dict_group)

    @tree.command(name='mute', description='特定のユーザーのメッセージを読み上げません')
    @app_commands.describe(user='読み上げを停止するユーザー')
    async def mute(interaction: discord.Interaction, user: discord.Member):
        await ensure_db_connection()

        if user.bot:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description='ボットのメッセージは読み上げられません。'), ephemeral=True)
            return

        if await db.is_user_muted(interaction.guild_id, user.id):
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description=f'{user.name}はすでにミュートされています。'), ephemeral=True)
            return

        try:
            await db.set_muted_user(interaction.guild_id, user.id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f'{user.name}のメッセージを読み上げないように設定しました。'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'ミュート設定に失敗しました: {str(e)}'))

    @tree.command(name='unmute', description='ミュートされたユーザーのメッセージを読み上げるようにします')
    @app_commands.describe(user='読み上げを再開するユーザー')
    async def unmute(interaction: discord.Interaction, user: discord.Member):
        await ensure_db_connection()

        if not await db.is_user_muted(interaction.guild_id, user.id):
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description=f'{user.name}はミュートになっていません。'), ephemeral=True)
            return

        try:
            await db.remove_muted_user(interaction.guild_id, user.id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f'{user.name}のメッセージを読み上げるように設定しました。'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'ミュート解除に失敗しました: {str(e)}'))

    @tree.command(name='mutelist', description='ミュートされているユーザーの一覧を表示します')
    async def mutelist(interaction: discord.Interaction):
        await ensure_db_connection()

        try:
            muted_users = await db.get_muted_users(interaction.guild_id)
            if not muted_users:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='ミュートされているユーザーはいません。'), ephemeral=True)
                return

            message = "ミュートされているユーザー一覧:\n"
            for user_id in muted_users:
                user = interaction.guild.get_member(user_id)
                if user:
                    message += f"- {user.name}\n"

            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.purple(), description=message), ephemeral=True)
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'ミュートユーザー一覧の取得に失敗しました: {str(e)}'))

    dynamic_group = app_commands.Group(name='dynamic_join', description='動的自動参加の設定')

    @dynamic_group.command(name='add', description='動的自動参加の設定を追加します')
    @app_commands.describe(
        text='読み上げるテキストチャンネル'
    )
    async def dynamic_join_add(interaction: discord.Interaction, text: discord.TextChannel):
        await ensure_db_connection()

        try:
            await db.set_dynamic_join(interaction.guild_id, text.id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f'動的自動参加の設定を追加しました。\nテキストチャンネル: {text.mention}'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'動的自動参加の設定に失敗しました: {str(e)}'))

    @dynamic_group.command(name='remove', description='動的自動参加の設定を削除します')
    @app_commands.describe(
        text='削除するテキストチャンネル'
    )
    async def dynamic_join_remove(interaction: discord.Interaction, text: discord.TextChannel):
        await ensure_db_connection()

        try:
            await db.remove_dynamic_join(interaction.guild_id, text.id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description=f'動的自動参加の設定を削除しました。\nテキストチャンネル: {text.mention}'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'動的自動参加の設定の削除に失敗しました: {str(e)}'))

    @dynamic_group.command(name='list', description='動的自動参加の設定一覧を表示します')
    async def dynamic_join_list(interaction: discord.Interaction):
        await ensure_db_connection()

        try:
            dynamic_joins = await db.get_dynamic_joins(interaction.guild_id)
            if not dynamic_joins:
                await interaction.response.send_message(embed=discord.Embed(color=discord.Color.dark_orange(), description='動的自動参加の設定はありません。'), ephemeral=True)
                return

            message = "動的自動参加の設定一覧:\n"
            for text_id in dynamic_joins:
                text = interaction.guild.get_channel(text_id)
                if text:
                    message += f"- テキストチャンネル: {text.name}\n"

            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.purple(), description=message), ephemeral=True)
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'動的自動参加の設定一覧の取得に失敗しました: {str(e)}'))

    tree.add_command(dynamic_group)

    @tree.command(name='change', description='読み上げるテキストチャンネルを変更します')
    async def change(interaction: discord.Interaction):
        await ensure_db_connection()

        try:
            await db.set_read_channel(interaction.guild_id, interaction.channel_id)
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.green(), description='読み上げるテキストチャンネルを変更しました。'))
        except Exception as e:
            await interaction.response.send_message(embed=discord.Embed(color=discord.Color.red(), description=f'読み上げるテキストチャンネルの変更に失敗しました: {str(e)}'))

def validate_voice_engine(engine: str, voice: str, config: dict, voice_characters: dict) -> tuple[bool, str]:
    if not config['engine_enabled'][engine]:
        return False, f'{engine}は無効になっています。'
    valid_voices = [v['value'] for v in voice_characters[engine_key[engine]]]
    if voice not in valid_voices:
        return False, f'無効な{engine}の音声が指定されました。'
    return True, ''

def get_voice_name(engine: str, voice: str, voice_characters: dict) -> str:
    for v in voice_characters[engine_key[engine]]:
        if v['value'] == voice:
            return v['name']
    return ''

def create_dict_pagination_response(replacements: dict, title: str, color: discord.Color = discord.Color.purple()) -> tuple[discord.Embed, View]:
    items = list(replacements.items())
    page_size = 10
    total_pages = math.ceil(len(items) / page_size)
    def embed_factory(items, page, page_size, total_pages):
        start = page * page_size
        end = start + page_size
        message = f"{title} (ページ {page+1}/{total_pages}):\n"
        for original, replacement in items[start:end]:
            message += f"- 「{original}」→「{replacement}」\n"
        return discord.Embed(color=color, description=message)
    view = PaginationView(items, page_size, embed_factory)
    view.children[0].disabled = True
    view.children[1].disabled = total_pages <= 1
    embed = embed_factory(items, 0, page_size, total_pages)
    return embed, view

class PaginationView(View):
    def __init__(self, items: list, page_size: int, embed_factory: callable, *, timeout: float = 60):
        super().__init__(timeout=timeout)
        self.items = items
        self.page_size = page_size
        self.total_pages = math.ceil(len(items) / page_size)
        self.page = 0
        self.embed_factory = embed_factory

    async def update_embed(self, interaction: discord.Interaction):
        embed = self.embed_factory(self.items, self.page, self.page_size, self.total_pages)
        await interaction.response.edit_message(embed=embed, view=self)

    @discord.ui.button(label='前へ', style=discord.ButtonStyle.secondary, disabled=True)
    async def prev(self, interaction: discord.Interaction, button: Button):
        if self.page > 0:
            self.page -= 1
        self.children[0].disabled = self.page == 0
        self.children[1].disabled = self.page >= self.total_pages - 1
        await self.update_embed(interaction)

    @discord.ui.button(label='次へ', style=discord.ButtonStyle.secondary, disabled=True)
    async def next(self, interaction: discord.Interaction, button: Button):
        if self.page < self.total_pages - 1:
            self.page += 1
        self.children[0].disabled = self.page == 0
        self.children[1].disabled = self.page >= self.total_pages - 1
        await self.update_embed(interaction)
