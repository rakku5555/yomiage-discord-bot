import aiohttp
from config import Config

class aivisspeech:
    def __init__(self, text: str, speaker: int, pitch: int, speed: float):
        self.config = Config.load_config()
        self.pitch = (pitch - 100) * 0.001
        self.speed = speed
        if self.config['aivisspeech']['edition']['engine']:
            self.params = {
                'text': text,
                'speaker': speaker
            }
        elif self.config['aivisspeech']['edition']['cloud']:
            self.data = {
                'model_uuid': 'a59cb814-0083-4369-8542-f51a29e72af7',
                'text': text,
                'pitch': self.pitch,
                'speaking_rate': self.speed,
                'output_format': 'opus',
                'output_sampling_rate': 48000,
            }

    async def get_audio(self) -> bytes:
        return await self._get_engine()

    async def _get_engine(self) -> bytes:
        async with aiohttp.ClientSession() as session:
            if self.config['aivisspeech']['edition']['engine']:
                url = self.config['aivisspeech']['url']
                json_response = await session.post(
                    f"{url}/audio_query",
                    headers = {
                        'Content-Type': 'application/json'
                    },
                    params = self.params
                )
                json_data = await json_response.json()
                if json_response.status != 200:
                    raise Exception(f"audio_queryのリクエストに失敗しました: {json_data['detail'][0]['msg']}")
                json_data['pitchScale'] = self.pitch
                json_data['speedScale'] = self.speed
                del self.params['text']
                response = await session.post(
                    f"{url}/synthesis",
                    headers = {
                        'Content-Type': 'application/json',
                        'Accept': 'audio/wav'
                    },
                    params = self.params,
                    json = json_data
                )
                if response.status != 200:
                    raise Exception(f"synthesisのリクエストに失敗しました: {await response.json()['detail'][0]['msg']}")
                return await response.read()
            elif self.config['aivisspeech']['edition']['cloud']:
                response = await session.post(
                    'https://api.aivis-project.com/v1/tts/synthesize',
                    headers = {
                        'Authorization': f"Bearer {self.config['aivisspeech']['apikey']}",
                        'Content-Type': 'application/json'
                    },
                    json = self.data
                )
                match response.status:
                    case 401:
                        raise Exception('APIキーが正しくありません')
                    case 402:
                        raise Exception('クレジット残高が不足しています')
                    case 404:
                        raise Exception('指定されたモデル UUID の音声合成モデルが見つかりません')
                    case 429:
                        raise Exception('APIレート制限に達しました')
                    case 500:
                        raise Exception('サーバーの接続中に不明なエラーが発生しました')
                    case 503:
                        raise Exception('サーバーの接続に失敗しました')
                    case 504:
                        raise Exception('サーバーの接続にタイムアウトしました')
                return await response.read()
