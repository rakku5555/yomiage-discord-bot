import re
import uuid

romaji_to_hiragana_map = {
    'a': 'あ', 'i': 'い', 'u': 'う', 'e': 'え', 'o': 'お',
    'ka': 'か', 'ki': 'き', 'ku': 'く', 'ke': 'け', 'ko': 'こ',
    'sa': 'さ', 'shi': 'し', 'si': 'し', 'su': 'す', 'se': 'せ', 'so': 'そ',
    'ta': 'た', 'chi': 'ち', 'ti': 'ち', 'tsu': 'つ', 'tu': 'つ', 'te': 'て', 'to': 'と',
    'na': 'な', 'ni': 'に', 'nu': 'ぬ', 'ne': 'ね', 'no': 'の',
    'ha': 'は', 'hi': 'ひ', 'fu': 'ふ', 'hu': 'ふ', 'he': 'へ', 'ho': 'ほ',
    'ma': 'ま', 'mi': 'み', 'mu': 'む', 'me': 'め', 'mo': 'も',
    'ya': 'や', 'yu': 'ゆ', 'yo': 'よ',
    'ra': 'ら', 'ri': 'り', 'ru': 'る', 're': 'れ', 'ro': 'ろ',
    'wa': 'わ', 'wo': 'を', 'n': 'ん',

    'ga': 'が', 'gi': 'ぎ', 'gu': 'ぐ', 'ge': 'げ', 'go': 'ご',
    'za': 'ざ', 'ji': 'じ', 'zi': 'じ', 'zu': 'ず', 'ze': 'ぜ', 'zo': 'ぞ',
    'da': 'だ', 'de': 'で', 'do': 'ど',
    'ba': 'ば', 'bi': 'び', 'bu': 'ぶ', 'be': 'べ', 'bo': 'ぼ',
    'pa': 'ぱ', 'pi': 'ぴ', 'pu': 'ぷ', 'pe': 'ぺ', 'po': 'ぽ',

    'kya': 'きゃ', 'kyu': 'きゅ', 'kyo': 'きょ',
    'sha': 'しゃ', 'shu': 'しゅ', 'sho': 'しょ',
    'sya': 'しゃ', 'syu': 'しゅ', 'syo': 'しょ',
    'cha': 'ちゃ', 'chu': 'ちゅ', 'cho': 'ちょ',
    'tya': 'ちゃ', 'tyu': 'ちゅ', 'tyo': 'ちょ',
    'nya': 'にゃ', 'nyu': 'にゅ', 'nyo': 'にょ',
    'hya': 'ひゃ', 'hyu': 'ひゅ', 'hyo': 'ひょ',
    'mya': 'みゃ', 'myu': 'みゅ', 'myo': 'みょ',
    'rya': 'りゃ', 'ryu': 'りゅ', 'ryo': 'りょ',
    'gya': 'ぎゃ', 'gyu': 'ぎゅ', 'gyo': 'ぎょ',
    'ja': 'じゃ', 'ju': 'じゅ', 'jo': 'じょ',
    'jya': 'じゃ', 'jyu': 'じゅ', 'jyo': 'じょ',
    'bya': 'びゃ', 'byu': 'びゅ', 'byo': 'びょ',
    'pya': 'ぴゃ', 'pyu': 'ぴゅ', 'pyo': 'ぴょ',

    'la': 'ぁ', 'li': 'ぃ', 'lu': 'ぅ', 'le': 'ぇ', 'lo': 'ぉ',
    'xa': 'ぁ', 'xi': 'ぃ', 'xu': 'ぅ', 'xe': 'ぇ', 'xo': 'ぉ',
    'lya': 'ゃ', 'lyi': 'ぃ', 'lyu': 'ゅ', 'lye': 'ぇ', 'lyo': 'ょ',
    'xya': 'ゃ', 'xyi': 'ぃ', 'xyu': 'ゅ', 'xye': 'ぇ', 'xyo': 'ょ',
    'ltu': 'っ', 'xtu': 'っ',
    'lwa': 'ゎ', 'xwa': 'ゎ',

    'va': 'ゔぁ', 'vi': 'ゔぃ', 'vu': 'ゔ', 've': 'ゔぇ', 'vo': 'ゔぉ',
    'vya': 'ゔゃ', 'vyu': 'ゔゅ', 'vyo': 'ゔょ',
    'she': 'しぇ', 'je': 'じぇ', 'che': 'ちぇ',
    'tsa': 'つぁ', 'tsi': 'つぃ', 'tse': 'つぇ', 'tso': 'つぉ',
    'dya': 'ぢゃ', 'dyu': 'ぢゅ', 'dyo': 'ぢょ',
    'dhi': 'でぃ', 'dhu': 'でゅ', 'dha': 'でゃ', 'dhe': 'でぇ', 'dho': 'でょ',
    'fa': 'ふぁ', 'fi': 'ふぃ', 'fe': 'ふぇ', 'fo': 'ふぉ', 'fyu': 'ふゅ',
    'kwa': 'くぁ', 'kwi': 'くぃ', 'kwe': 'くぇ', 'kwo': 'くぉ',
    'gwa': 'ぐぁ', 'gwi': 'ぐぃ', 'gwe': 'ぐぇ', 'gwo': 'ぐぉ',
    'ye': 'いぇ', 'wi': 'うぃ', 'we': 'うぇ',
}

def is_romaji(text: str) -> bool:
    romaji_pattern = re.compile(r"^[a-zA-Z0-9\s.,'?!-]*$")
    return bool(romaji_pattern.fullmatch(text))

def romaji_to_hiragana(romaji_text: str) -> str:
    if not is_romaji(romaji_text):
        return romaji_text

    non_romaji = re.findall(r"[^a-zA-Z]+", romaji_text)
    placeholders = {}

    for _, part in enumerate(non_romaji):
        placeholder = f"__PLACEHOLDER_{uuid.uuid4()}__"
        placeholders[placeholder] = part
        romaji_text = romaji_text.replace(part, placeholder, 1)

    words = re.findall(r"[a-zA-Z]+|__PLACEHOLDER_[0-9a-fA-F\-]{36}__", romaji_text)
    result = []

    for word in words:
        if word in placeholders:
            result.append(word)
            continue
        if not re.fullmatch(r"[a-z]+", word):
            result.append(word)
            continue

        i = 0
        hiragana = []
        failed = False
        while i < len(word):
            if i + 1 < len(word) and word[i:i+2] == 'nn':
                hiragana.append('ん')
                i += 2
                continue

            if i + 1 < len(word) and word[i] == word[i+1] and word[i] not in 'aeiouy':
                hiragana.append('っ')
                i += 1
                continue

            if word[i] == 'n' and (i + 1 == len(word) or word[i+1] not in 'aeiouy'):
                hiragana.append('ん')
                i += 1
                continue

            matched = False
            for length in (3, 2, 1):
                chunk = word[i:i+length]
                if chunk in romaji_to_hiragana_map:
                    hiragana.append(romaji_to_hiragana_map[chunk])
                    i += length
                    matched = True
                    break

            if matched:
                continue
            else:
                failed = True
                break

        if failed:
            result.append(word)
        else:
            result.append("".join(hiragana))

    final = "".join(result)
    for placeholder, value in placeholders.items():
        final = final.replace(placeholder, value)
    return final
