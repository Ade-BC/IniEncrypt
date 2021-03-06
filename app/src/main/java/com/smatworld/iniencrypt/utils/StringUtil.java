package com.smatworld.iniencrypt.utils;

import android.util.Log;

import java.util.Locale;

import static com.smatworld.iniencrypt.utils.Constants.TAG;

public class StringUtil {
    private StringUtil() {
    }

    public static String getFormattedSize(long fileSize) {
        StringBuilder result = new StringBuilder();
        int charCount = String.valueOf(fileSize).trim().length();

        if (charCount <= 3)
            result.append(String.format(Locale.getDefault(), "%d", fileSize)).append(" B");

        else if (charCount > 3 && charCount < 7)
            result.append(String.format(Locale.getDefault(), "%.2f", fileSize / (1024.0))).append(" KB");

        else if (charCount >= 7 && charCount < 10)
            result.append(String.format(Locale.getDefault(), "%.2f", fileSize / (1024 * 1024.0))).append(" MB");

        else if (charCount >= 10)
            result.append(String.format(Locale.getDefault(), "%.2f", fileSize / (1024 * 1024 * 1024.0))).append(" GB");

        else throw new IllegalArgumentException("Invalid argument passed: " + fileSize);
        return result.toString();
    }

    public static boolean canUseRSA(long fileSize, int keyLength) {
        int maxRSAFileSize = (keyLength / 8) - 11;
        Log.i(TAG, "canUseRSA: maxRSAFileSize: " + maxRSAFileSize);
        return fileSize <= maxRSAFileSize;
    }
}
