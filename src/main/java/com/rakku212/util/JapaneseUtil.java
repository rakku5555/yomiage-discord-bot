package com.rakku212.util;

public final class JapaneseUtil {

    public static String hiraToKatakana(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u3041' && ch <= '\u3096') {
                result.append((char) (ch + 0x60));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
