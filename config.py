import aiofiles
import os
import yaml
from typing import Any

class Config:
    _config_path = os.path.join(os.path.dirname(__file__), 'config.yaml')
    _ERROR_MESSAGES = {
        'file_not_found': "設定ファイルが見つかりません: {}",
        'invalid_format': "設定ファイルの形式が正しくありません: {}"
    }

    @classmethod
    def _handle_config_errors(cls, error: Exception) -> None:
        if isinstance(error, FileNotFoundError):
            raise FileNotFoundError(cls._ERROR_MESSAGES['file_not_found'].format(cls._config_path))
        if isinstance(error, yaml.YAMLError):
            raise ValueError(cls._ERROR_MESSAGES['invalid_format'].format(cls._config_path))
        raise error

    @classmethod
    def load_config(cls) -> dict[str, Any]:
        try:
            with open(cls._config_path, encoding='utf-8') as f:
                return yaml.safe_load(f)
        except Exception as e:
            cls._handle_config_errors(e)

    @classmethod
    async def async_load_config(cls) -> dict[str, Any]:
        try:
            async with aiofiles.open(cls._config_path, encoding='utf-8') as f:
                return yaml.safe_load(await f.read())
        except Exception as e:
            cls._handle_config_errors(e)
