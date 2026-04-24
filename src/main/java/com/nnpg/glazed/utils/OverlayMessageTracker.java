package com.nnpg.glazed.utils;

import net.minecraft.text.Text;

import java.util.Locale;

public final class OverlayMessageTracker {
    private static volatile String lastOverlayMessage = "";
    private static volatile long lastOverlayTimestamp = 0L;
    private static volatile long lastDeliveringOverlayTimestamp = 0L;

    private OverlayMessageTracker() {
    }

    public static void onOverlayMessage(Text text) {
        lastOverlayMessage = text == null ? "" : text.getString();
        lastOverlayTimestamp = System.currentTimeMillis();

        if (lastOverlayMessage.toLowerCase(Locale.ROOT).contains("delivering")) {
            lastDeliveringOverlayTimestamp = lastOverlayTimestamp;
        }
    }

    public static String getLastOverlayMessage() {
        return lastOverlayMessage;
    }

    public static long getLastOverlayTimestamp() {
        return lastOverlayTimestamp;
    }

    public static boolean sawDeliveringOverlaySince(long timestamp) {
        return lastDeliveringOverlayTimestamp >= timestamp;
    }
}
