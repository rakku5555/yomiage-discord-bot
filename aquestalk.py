import ctypes
import os
import platform
from config import Config

class aquestalk1:
    def __init__(self, text: str, voice_name: str, speed: int):
        self.text = text
        self.speed = speed

        match platform.system().lower():
            case 'windows':
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk1', 'lib', voice_name, 'AquesTalk.dll')
            case 'linux':
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk1', 'lib', voice_name, 'libAquesTalk.so')

        self.aquestalk = ctypes.CDLL(path)

        self.aquestalk.AquesTalk_Synthe_Utf8.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        self.aquestalk.AquesTalk_Synthe_Utf8.restype = ctypes.POINTER(ctypes.c_ubyte)
        self.aquestalk.AquesTalk_FreeWave.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        self.aquestalk.AquesTalk_FreeWave.restype = None

    async def get_audio(self) -> bytes:
        size = ctypes.c_int(0)

        self.wav_data = self.aquestalk.AquesTalk_Synthe_Utf8(self.text.encode(), self.speed, ctypes.byref(size))
        if not self.wav_data:
            raise RuntimeError('音声合成に失敗しました')

        return bytes(self.wav_data[:size.value])

    def __del__(self):
        self.aquestalk.AquesTalk_FreeWave(self.wav_data)

class aquestalk2:
    def __init__(self, text: str, voice_name: str, speed: int):
        self.text = text
        self.speed = speed

        match platform.system().lower():
            case 'windows':
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk2', 'lib', 'AquesTalk2.dll')
            case 'linux':
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk2', 'lib', 'libAquesTalk2.so')

        phont_file = os.path.join(os.path.dirname(__file__), 'AquesTalk2', 'phont', f"{voice_name}.phont")

        self.aquestalk = ctypes.CDLL(path)

        self.aquestalk.AquesTalk2_Synthe_Utf8.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.POINTER(ctypes.c_int), ctypes.c_void_p]
        self.aquestalk.AquesTalk2_Synthe_Utf8.restype = ctypes.POINTER(ctypes.c_ubyte)
        self.aquestalk.AquesTalk2_FreeWave.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        self.aquestalk.AquesTalk2_FreeWave.restype = None

        try:
            with open(phont_file, 'rb') as f:
                data = f.read()
                self.phont_ptr = ctypes.cast(ctypes.create_string_buffer(data), ctypes.c_void_p)
        except Exception as e:
            raise e

        if self.phont_ptr is None:
            raise RuntimeError(f"Phontファイルの読み込みに失敗しました: {phont_file}")

    async def get_audio(self) -> bytes:
        size = ctypes.c_int(0)

        self.wav_data = self.aquestalk.AquesTalk2_Synthe_Utf8(self.text.encode(), self.speed, ctypes.byref(size), self.phont_ptr)

        return bytes(self.wav_data[:size.value])

    def __del__(self):
        self.aquestalk.AquesTalk2_FreeWave(self.wav_data)

class aquestalk10:
    _instance = None
    VOICE_BASE_MAP = {
        'f1e': 0,
        'f2e': 1,
        'm1e': 2,
    }

    class AQTK_VOICE(ctypes.Structure):
        _fields_ = [
            ("bas", ctypes.c_int),
            ("spd", ctypes.c_int),
            ("vol", ctypes.c_int),
            ("pit", ctypes.c_int),
            ("acc", ctypes.c_int),
            ("lmd", ctypes.c_int),
            ("fsc", ctypes.c_int)
        ]

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self, text: str, voice_name: str, speed: int = 100, pitch: int = 100, accent: int = 100, lmd: int = 100):
        self.text = text

        voice_base = self.VOICE_BASE_MAP[voice_name.lower()]
        
        self.aqtk_voice = aquestalk10.AQTK_VOICE(voice_base, speed, 100, pitch, accent, lmd, 100)

        if self._initialized:
            return

        match platform.system().lower():
            case 'windows':
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk10', 'lib', 'AquesTalk.dll')
            case 'linux':
                ctypes.CDLL('libstdc++.so.6', ctypes.RTLD_GLOBAL)
                path = os.path.join(os.path.dirname(__file__), 'AquesTalk10', 'lib', 'libAquesTalk10.so')

        self.aquestalk = ctypes.CDLL(path)

        self.aquestalk.AquesTalk_Synthe_Utf8.argtypes = [ctypes.POINTER(aquestalk10.AQTK_VOICE), ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
        self.aquestalk.AquesTalk_Synthe_Utf8.restype = ctypes.POINTER(ctypes.c_ubyte)
        self.aquestalk.AquesTalk_SetDevKey.argtypes = [ctypes.c_char_p]
        self.aquestalk.AquesTalk_SetDevKey.restype = ctypes.c_int
        self.aquestalk.AquesTalk_FreeWave.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        self.aquestalk.AquesTalk_FreeWave.restype = None

        if Config.load_config()['dev_key']['aquestalk10'] is not None:
            self.aquestalk.AquesTalk_SetDevKey(Config.load_config()['dev_key']['aquestalk10'].encode())
        self._initialized = True

    async def get_audio(self) -> bytes:
        try:
            size = ctypes.c_int(0)
            self.wav_data = self.aquestalk.AquesTalk_Synthe_Utf8(ctypes.byref(self.aqtk_voice), self.text.encode(), ctypes.byref(size))

            return bytes(self.wav_data[:size.value])
        finally:
            self.aquestalk.AquesTalk_FreeWave(self.wav_data)
