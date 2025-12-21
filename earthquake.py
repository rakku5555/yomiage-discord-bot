import asyncio
import json
from datetime import datetime

import aiohttp
from loguru import logger

from vc import read_message

discord_client = None

def convert_max_scale_to_intensity(max_scale: int):
    scale_map = {
        -1: "震度情報なし",
        10: "震度1",
        20: "震度2",
        30: "震度3",
        40: "震度4",
        45: "震度5弱",
        50: "震度5強",
        55: "震度6弱",
        60: "震度6強",
        70: "震度7"
    }
    return scale_map.get(max_scale, f"不明な震度({max_scale})")

async def websocket_client():
    url = "wss://api.p2pquake.net/v2/ws"

    while True:
        try:
            async with aiohttp.ClientSession() as session, session.ws_connect(url) as ws:
                logger.success("P2P地震情報のWebSocket接続が確立されました")

                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.TEXT:
                        data = json.loads(msg.data)
                        if data["code"] != 551:
                            continue
                        earthquake = {
                            "time": data["earthquake"]["time"],
                            "name": data["earthquake"]["hypocenter"]["name"],
                            "depth": data["earthquake"]["hypocenter"]["depth"],
                            "magnitude": data["earthquake"]["hypocenter"]["magnitude"],
                            "intensity": convert_max_scale_to_intensity(data["earthquake"]["maxScale"])
                        }
                        if data["earthquake"]["maxScale"] >= 45:
                            await earthquake_broadcast(earthquake)
                    elif msg.type == aiohttp.WSMsgType.ERROR:
                        logger.error(f"WebSocketエラー: {ws.exception()}")
                        continue
                    elif msg.type == aiohttp.WSMsgType.CLOSED:
                        logger.info("WebSocket接続が閉じられました")
                        break
        except Exception as e:
            logger.error(f"WebSocket接続エラー: {e}")

        logger.info("5秒後に再接続を試行します...")
        await asyncio.sleep(5)

async def earthquake_broadcast(earthquake):
    match earthquake["magnitude"]:
        case -1:
            magnitude_text = "マグニチュードは不明"
        case _:
            magnitude_text = f"マグニチュードは{earthquake["magnitude"]}"

    match earthquake["depth"]:
        case -1:
            depth_text = "深さは不明"
        case 0:
            depth_text = "深さはごく浅い"
        case _:
            depth_text = f"深さは{earthquake["depth"]}キロメートル"

    time_str = earthquake["time"]
    dt = datetime.strptime(time_str, "%Y/%m/%d %H:%M:%S")

    formatted_time = f"{dt.year}年{dt.month}月{dt.day}日 {dt.hour}時{dt.minute}分"

    message = f"地震情報です。{formatted_time}に、{earthquake["name"]}で{earthquake["intensity"]}の地震が発生しました。{magnitude_text}、{depth_text}です。"

    for guild in discord_client.guilds:
        voice_client = guild.voice_client

        if voice_client is None or not voice_client.is_connected():
            continue

        try:
            await read_message(message, guild, None, None)
            logger.info(f"{guild.name}のボイスチャンネルに地震情報をブロードキャストしました")
        except Exception as e:
            logger.error(f"{guild.name}での地震情報ブロードキャストに失敗しました: {e}")

def set_discord_client(client):
    global discord_client
    discord_client = client
