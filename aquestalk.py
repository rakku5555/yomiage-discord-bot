import aiohttp

from config import Config


class aquestalk:
    def __init__(
        self,
        text: str,
        engine: str,
        voice_name: str,
        speed: int = 100,
        pitch: int = 100,
        accent: int = 100,
        lmd: int = 100,
    ):
        self.data = {
            "text": text,
            "engine": engine,
            "voice": voice_name,
            "speed": speed,
            "pitch": pitch,
            "accent": accent,
            "lmd": lmd,
        }
        self.url = Config.load_config()["aquestalk"]["url"]

    async def _get_engine(self) -> bytes:
        async with aiohttp.ClientSession(self.url) as session:
            response = await session.post(
                "/synthesis",
                headers={"Content-Type": "application/json", "Accept": "audio/wav"},
                json=self.data,
            )
            if response.status != 200:
                raise Exception("synthesisのリクエストに失敗しました")
            return await response.read()

    async def get_audio(self) -> bytes:
        return await self._get_engine()
