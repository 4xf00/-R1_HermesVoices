package com.hermes.r1voice;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * Chinese character to KWS token sequence converter.
 * Loads mapping from res/raw/pinyin.txt (format: "HEXCODE,tokens").
 * Each character maps to a space-separated token sequence for sherpa-onnx KWS.
 */
public class ChinesePinyin {
    private static HashMap<Character, String> tokenMap = null;

    public static void init(Context ctx) {
        if (tokenMap != null) return;
        tokenMap = new HashMap<Character, String>();
        try {
            InputStream is = ctx.getResources().openRawResource(
                ctx.getResources().getIdentifier("pinyin", "raw", ctx.getPackageName()));
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                int comma = line.indexOf(',');
                if (comma > 0 && comma < line.length() - 1) {
                    try {
                        int cp = Integer.parseInt(line.substring(0, comma), 16);
                        String tokens = line.substring(comma + 1);
                        tokenMap.put((char) cp, tokens);
                    } catch (Exception e) {}
                }
            }
            br.close();
        } catch (Exception e) {}
    }

    /**
     * Convert Chinese wake word to KWS keyword line.
     * Non-CJK chars (English, numbers) are kept as-is.
     * Output: "token_sequence @display_name"
     * Example: "n ǐ h ǎo @你好 Tommy"
     */
    public static String toKeywordsLine(String wakeWord) {
        if (wakeWord == null || wakeWord.isEmpty() || tokenMap == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wakeWord.length(); i++) {
            char c = wakeWord.charAt(i);
            String tokens = tokenMap.get(c);
            if (tokens != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(tokens);
            } else if (c == ' ') {
                if (sb.length() > 0) sb.append(' ');
            } else {
                // Non-CJK: keep as-is
                if (sb.length() > 0) sb.append(' ');
                sb.append(c);
            }
        }
        if (sb.length() == 0) return null;
        return sb.toString() + " @" + wakeWord;
    }
}
