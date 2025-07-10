import aiohttp
import os
import platform
from config import Config
from loguru import logger
from pathlib import Path
from voicevox_core.asyncio import Onnxruntime, OpenJtalk, Synthesizer, VoiceModelFile

class VoicevoxConfig:
    def get_default_config() -> dict:
        base_dir = os.path.dirname(os.path.abspath(__file__))
        match platform.system().lower():
            case 'windows':
                onnxruntime_path = os.path.join(base_dir, 'voicevox', 'onnxruntime', 'lib', 'voicevox_onnxruntime.dll')
            case 'linux':
                onnxruntime_path = os.path.join(base_dir, 'voicevox', 'onnxruntime', 'lib', 'libvoicevox_onnxruntime.so')

        return {
            'vvm_path': os.path.join(base_dir, 'voicevox', 'models', 'vvms'),
            'onnxruntime_path': onnxruntime_path,
            'dict_dir': os.path.join(base_dir, 'voicevox', 'dict', 'open_jtalk_dic_utf')
        }

class voicevox:
    _instance = None
    _synthesizer = None
    _initialized = False

    def __init__(self, text: str, style_id: int, pitch: int, speed: float):
        self.text = text
        self.style_id = style_id
        self.pitch = (pitch - 100) * 0.001
        self.speed = speed
        self.voicevox_config = VoicevoxConfig.get_default_config()
        self.config = Config.load_config()
        if self.config['voicevox']['edition']['engine']:
            self.url = self.config['voicevox']['url']
            self.params = {
                'text': text,
                'speaker': style_id
            }

        if voicevox._instance is None:
            voicevox._instance = self

    @classmethod
    async def init(cls) -> None:
        if cls._instance is None:
            cls._instance = cls('', 0, 100, 1.0)

        if not cls._initialized:
            if cls._synthesizer is None:
                onnxruntime = await Onnxruntime.load_once(filename=cls._instance.voicevox_config['onnxruntime_path'])
                open_jtalk = await OpenJtalk.new(cls._instance.voicevox_config['dict_dir'])

                cls._synthesizer = Synthesizer(onnxruntime, open_jtalk)

            model_count = 0
            model_loaded = set()
            for model_file in Path(cls._instance.voicevox_config['vvm_path']).glob('*.vvm'):
                try:
                    model_id = model_file.stem
                    if model_id not in model_loaded:
                        async with await VoiceModelFile.open(model_file) as model:
                            await cls._synthesizer.load_voice_model(model)
                        if cls._instance.config['debug']:
                            logger.debug(f"モデル {model_file.name} を読み込みました")
                        model_count += 1
                        model_loaded.add(model_id)
                except Exception as e:
                    logger.error(f"モデル {model_file.name} の読み込みに失敗しました: {e}")
                    continue

            cls._initialized = True
            logger.success('voicevoxの初期化に成功しました')

    async def get_audio(self) -> bytes:
        try:
            if self.config['debug']:
                logger.debug(f"音声生成を開始 - テキスト: {self.text}, スタイルID: {self.style_id}, 速度: {self.speed}")
            if self.config['voicevox']['edition']['core']:
                if voicevox._synthesizer is None:
                    raise RuntimeError('シンセサイザーが初期化されていません')

                audio_query = await voicevox._synthesizer.create_audio_query(self.text, self.style_id)
                audio_query.pitch_scale = self.pitch
                audio_query.speed_scale = self.speed
                wav = await voicevox._synthesizer.synthesis(audio_query, self.style_id)

            elif self.config['voicevox']['edition']['engine']:
                wav = await self._get_engine()
            else:
                raise RuntimeError('voicevoxのエンジンが有効になっていません')

            return wav

        except Exception as e:
            raise e

    async def _get_engine(self) -> bytes:
        async with aiohttp.ClientSession(self.url) as session:
            json_response = await session.post(
                '/audio_query',
                headers={
                    'Content-Type': 'application/json'
                },
                params=self.params
            )
            json_data = await json_response.json()
            if json_response.status != 200:
                raise Exception(f"audio_queryのリクエストに失敗しました: {json_data['detail'][0]['msg']}")
            json_data['pitchScale'] = self.pitch
            json_data['speedScale'] = self.speed
            del self.params['text']
            response = await session.post(
                '/synthesis',
                headers={
                    'Content-Type': 'application/json',
                    'Accept': 'audio/wav'
                },
                params=self.params,
                json=json_data
            )
            if response.status != 200:
                raise Exception(f"synthesisのリクエストに失敗しました: {await response.json()['detail'][0]['msg']}")
            return await response.read()
