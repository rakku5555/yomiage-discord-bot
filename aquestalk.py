import ctypes
import os
import platform

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

        self.wav_data = self.aquestalk.AquesTalk_Synthe_Utf8(self.text.encode('utf-8'), self.speed, ctypes.byref(size))

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

        self.wav_data = self.aquestalk.AquesTalk2_Synthe_Utf8(self.text.encode('utf-8'), self.speed, ctypes.byref(size), self.phont_ptr)

        return bytes(self.wav_data[:size.value])

    def __del__(self):
        self.aquestalk.AquesTalk2_FreeWave(self.wav_data)
