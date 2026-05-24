package com.rakku212.voice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextConverters {
    private static final Pattern ROMAJI_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s.,'?!-]*$");
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+|__PLACEHOLDER_[0-9a-fA-F\\-]{36}__");
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://|www\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern W_SLANG_PATTERN = Pattern.compile("(\\s*)(w+)$");

    private static final Map<String, String> ROMAJI_TO_HIRAGANA = buildRomajiMap();

    public static boolean isRomaji(String text) {
        return ROMAJI_PATTERN.matcher(text).matches();
    }

    public static String romajiToHiragana(String romajiText) {
        if (!isRomaji(romajiText)) {
            return romajiText;
        }

        Map<String, String> placeholders = new LinkedHashMap<>();
        Matcher nonRomajiMatcher = Pattern.compile("[^a-zA-Z]+").matcher(romajiText);
        StringBuilder working = new StringBuilder(romajiText);
        while (nonRomajiMatcher.find()) {
            String part = nonRomajiMatcher.group();
            String placeholder = "__PLACEHOLDER_" + UUID.randomUUID() + "__";
            placeholders.put(placeholder, part);
            int index = working.indexOf(part);
            if (index >= 0) {
                working.replace(index, index + part.length(), placeholder);
            }
        }

        Matcher wordMatcher = WORD_PATTERN.matcher(working);
        StringBuilder result = new StringBuilder();
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            if (placeholders.containsKey(word)) {
                result.append(word);
                continue;
            }
            result.append(convertWord(word));
        }

        String finalText = result.toString();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            finalText = finalText.replace(entry.getKey(), entry.getValue());
        }
        return finalText;
    }

    public static String wToWaraConverter(String text) {
        if (URL_PATTERN.matcher(text).find()) {
            return text;
        }
        Matcher matcher = W_SLANG_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        return matcher.replaceAll(match -> match.group(1) + "ワラ".repeat(match.group(2).length()));
    }

    private static String convertWord(String word) {
        if (!word.matches("[a-z]+")) {
            return word;
        }
        StringBuilder hiragana = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            if (i + 1 < word.length() && word.startsWith("nn", i)) {
                hiragana.append('ん');
                i += 2;
                continue;
            }
            if (i + 1 < word.length() && word.charAt(i) == word.charAt(i + 1) && "aeiouy".indexOf(word.charAt(i)) < 0) {
                hiragana.append('っ');
                i++;
                continue;
            }
            if (word.charAt(i) == 'n' && (i + 1 == word.length() || "aeiouy".indexOf(word.charAt(i + 1)) < 0)) {
                hiragana.append('ん');
                i++;
                continue;
            }
            boolean matched = false;
            for (int length : new int[]{3, 2, 1}) {
                if (i + length > word.length()) {
                    continue;
                }
                String chunk = word.substring(i, i + length);
                String converted = ROMAJI_TO_HIRAGANA.get(chunk);
                if (converted != null) {
                    hiragana.append(converted);
                    i += length;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return word;
            }
        }
        return hiragana.toString();
    }

    private static Map<String, String> buildRomajiMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("a", "あ");
        map.put("i", "い");
        map.put("u", "う");
        map.put("e", "え");
        map.put("o", "お");
        map.put("ka", "か");
        map.put("ki", "き");
        map.put("ku", "く");
        map.put("ke", "け");
        map.put("ko", "こ");
        map.put("sa", "さ");
        map.put("shi", "し");
        map.put("si", "し");
        map.put("su", "す");
        map.put("se", "せ");
        map.put("so", "そ");
        map.put("ta", "た");
        map.put("chi", "ち");
        map.put("ti", "ち");
        map.put("tsu", "つ");
        map.put("tu", "つ");
        map.put("te", "て");
        map.put("to", "と");
        map.put("na", "な");
        map.put("ni", "に");
        map.put("nu", "ぬ");
        map.put("ne", "ね");
        map.put("no", "の");
        map.put("ha", "は");
        map.put("hi", "ひ");
        map.put("fu", "ふ");
        map.put("hu", "ふ");
        map.put("he", "へ");
        map.put("ho", "ほ");
        map.put("ma", "ま");
        map.put("mi", "み");
        map.put("mu", "む");
        map.put("me", "め");
        map.put("mo", "も");
        map.put("ya", "や");
        map.put("yu", "ゆ");
        map.put("yo", "よ");
        map.put("ra", "ら");
        map.put("ri", "り");
        map.put("ru", "る");
        map.put("re", "れ");
        map.put("ro", "ろ");
        map.put("wa", "わ");
        map.put("wo", "を");
        map.put("n", "ん");
        map.put("ga", "が");
        map.put("gi", "ぎ");
        map.put("gu", "ぐ");
        map.put("ge", "げ");
        map.put("go", "ご");
        map.put("za", "ざ");
        map.put("ji", "じ");
        map.put("zi", "じ");
        map.put("zu", "ず");
        map.put("ze", "ぜ");
        map.put("zo", "ぞ");
        map.put("da", "だ");
        map.put("de", "で");
        map.put("do", "ど");
        map.put("ba", "ば");
        map.put("bi", "び");
        map.put("bu", "ぶ");
        map.put("be", "べ");
        map.put("bo", "ぼ");
        map.put("pa", "ぱ");
        map.put("pi", "ぴ");
        map.put("pu", "ぷ");
        map.put("pe", "ぺ");
        map.put("po", "ぽ");
        map.put("kya", "きゃ");
        map.put("kyu", "きゅ");
        map.put("kyo", "きょ");
        map.put("sha", "しゃ");
        map.put("shu", "しゅ");
        map.put("sho", "しょ");
        map.put("sya", "しゃ");
        map.put("syu", "しゅ");
        map.put("syo", "しょ");
        map.put("cha", "ちゃ");
        map.put("chu", "ちゅ");
        map.put("cho", "ちょ");
        map.put("tya", "ちゃ");
        map.put("tyu", "ちゅ");
        map.put("tyo", "ちょ");
        map.put("nya", "にゃ");
        map.put("nyu", "にゅ");
        map.put("nyo", "にょ");
        map.put("hya", "ひゃ");
        map.put("hyu", "ひゅ");
        map.put("hyo", "ひょ");
        map.put("mya", "みゃ");
        map.put("myu", "みゅ");
        map.put("myo", "みょ");
        map.put("rya", "りゃ");
        map.put("ryu", "りゅ");
        map.put("ryo", "りょ");
        map.put("gya", "ぎゃ");
        map.put("gyu", "ぎゅ");
        map.put("gyo", "ぎょ");
        map.put("ja", "じゃ");
        map.put("ju", "じゅ");
        map.put("jo", "じょ");
        map.put("jya", "じゃ");
        map.put("jyu", "じゅ");
        map.put("jyo", "じょ");
        map.put("bya", "びゃ");
        map.put("byu", "びゅ");
        map.put("byo", "びょ");
        map.put("pya", "ぴゃ");
        map.put("pyu", "ぴゅ");
        map.put("pyo", "ぴょ");
        map.put("la", "ぁ");
        map.put("li", "ぃ");
        map.put("lu", "ぅ");
        map.put("le", "ぇ");
        map.put("lo", "ぉ");
        map.put("xa", "ぁ");
        map.put("xi", "ぃ");
        map.put("xu", "ぅ");
        map.put("xe", "ぇ");
        map.put("xo", "ぉ");
        map.put("lya", "ゃ");
        map.put("lyi", "ぃ");
        map.put("lyu", "ゅ");
        map.put("lye", "ぇ");
        map.put("lyo", "ょ");
        map.put("xya", "ゃ");
        map.put("xyi", "ぃ");
        map.put("xyu", "ゅ");
        map.put("xye", "ぇ");
        map.put("xyo", "ょ");
        map.put("ltu", "っ");
        map.put("xtu", "っ");
        map.put("lwa", "ゎ");
        map.put("xwa", "ゎ");
        map.put("va", "ゔぁ");
        map.put("vi", "ゔぃ");
        map.put("vu", "ゔ");
        map.put("ve", "ゔぇ");
        map.put("vo", "ゔぉ");
        map.put("vya", "ゔゃ");
        map.put("vyu", "ゔゅ");
        map.put("vyo", "ゔょ");
        map.put("she", "しぇ");
        map.put("je", "じぇ");
        map.put("che", "ちぇ");
        map.put("tsa", "つぁ");
        map.put("tsi", "つぃ");
        map.put("tse", "つぇ");
        map.put("tso", "つぉ");
        map.put("dya", "ぢゃ");
        map.put("dyu", "ぢゅ");
        map.put("dyo", "ぢょ");
        map.put("dhi", "でぃ");
        map.put("dhu", "でゅ");
        map.put("dha", "でゃ");
        map.put("dhe", "でぇ");
        map.put("dho", "でょ");
        map.put("fa", "ふぁ");
        map.put("fi", "ふぃ");
        map.put("fe", "ふぇ");
        map.put("fo", "ふぉ");
        map.put("fyu", "ふゅ");
        map.put("kwa", "くぁ");
        map.put("kwi", "くぃ");
        map.put("kwe", "くぇ");
        map.put("kwo", "くぉ");
        map.put("gwa", "ぐぁ");
        map.put("gwi", "ぐぃ");
        map.put("gwe", "ぐぇ");
        map.put("gwo", "ぐぉ");
        map.put("ye", "いぇ");
        map.put("wi", "うぃ");
        map.put("we", "うぇ");
        return map;
    }
}
