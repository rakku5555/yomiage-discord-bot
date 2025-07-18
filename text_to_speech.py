import ctypes
import os
import platform
from config import Config

class text_to_speech:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return

        self.system = platform.system().lower()
        self.dll_dir = os.path.join(os.path.dirname(__file__), 'AqKanji2Koe', 'lib')
        self.dic_dir = os.path.join(os.path.dirname(__file__), 'AqKanji2Koe', 'aq_dic')

        match self.system:
            case 'windows':
                path = os.path.join(self.dll_dir, 'AqKanji2Koe.dll')
            case 'linux':
                path = os.path.join(self.dll_dir, 'libAqKanji2Koe.so')

        self.aq_kanji2koe = ctypes.CDLL(path)

        self.aq_kanji2koe.AqKanji2Koe_Create.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
        self.aq_kanji2koe.AqKanji2Koe_Create.restype = ctypes.c_void_p
        self.aq_kanji2koe.AqKanji2Koe_SetDevKey.argtypes = [ctypes.c_char_p]
        self.aq_kanji2koe.AqKanji2Koe_SetDevKey.restype = ctypes.c_int
        self.aq_kanji2koe.AqKanji2Koe_Release.argtypes = [ctypes.c_void_p]
        self.aq_kanji2koe.AqKanji2Koe_Release.restype = None

        match self.system:
            case 'windows':
                self.aq_kanji2koe.AqKanji2Koe_Convert_utf8.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
                self.aq_kanji2koe.AqKanji2Koe_Convert_utf8.restype = ctypes.c_int
            case 'linux':
                self.aq_kanji2koe.AqKanji2Koe_Convert.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
                self.aq_kanji2koe.AqKanji2Koe_Convert.restype = ctypes.c_int

        if Config.load_config()['dev_key']['aqkanji2koe'] is not None:
            self.aq_kanji2koe.AqKanji2Koe_SetDevKey(Config.load_config()['dev_key']['aqkanji2koe'].encode())
        self._initialized = True

    def convert(self, message: str) -> str:
        err_code = ctypes.c_int(0)
        instance = self.aq_kanji2koe.AqKanji2Koe_Create(self.dic_dir.encode('utf-8'), ctypes.byref(err_code))
        if not instance:
            raise Exception(f"AqKanji2Koeインスタンスの作成に失敗しました (エラーコード: {err_code.value})")

        try:
            if self.system == 'windows':
                output_buffer = ctypes.create_string_buffer(4096)
                result = self.aq_kanji2koe.AqKanji2Koe_Convert_utf8(instance, message.encode(), output_buffer, 4096)
                if result == 0:
                    return output_buffer.value.decode('utf-8')
                else:
                    raise Exception(f"変換に失敗しました。エラーコード: {result}")
            elif self.system == 'linux':
                output_buffer = ctypes.create_string_buffer(4096)
                result = self.aq_kanji2koe.AqKanji2Koe_Convert(instance, message.encode(), output_buffer, 4096)
                if result == 0:
                    return output_buffer.value.decode('utf-8')
                else:
                    raise Exception(f"変換に失敗しました。エラーコード: {result}")
        finally:
            self.aq_kanji2koe.AqKanji2Koe_Release(ctypes.c_void_p(instance))
