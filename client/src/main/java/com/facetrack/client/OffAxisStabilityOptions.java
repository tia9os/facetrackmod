package com.facetrack.client;

record OffAxisStabilityOptions(
        OffAxisMode mode,
        int holdMillis,
        double minFrontalQuality
) {
    private static final OffAxisMode DEFAULT_MODE = OffAxisMode.HOLD_LAST;
    private static final int DEFAULT_HOLD_MILLIS = 700;
    private static final double DEFAULT_MIN_FRONTAL_QUALITY = 0.45;

    OffAxisStabilityOptions {
        mode = mode == null ? DEFAULT_MODE : mode;
        holdMillis = Math.max(0, Math.min(5000, holdMillis));
        minFrontalQuality = Math.max(0.0, Math.min(1.0, minFrontalQuality));
    }

    static OffAxisStabilityOptions defaults() {
        return new OffAxisStabilityOptions(
                OffAxisMode.fromProperty(System.getProperty("facetrack.offaxis.mode"), DEFAULT_MODE),
                intProperty("facetrack.offaxis.holdMillis", DEFAULT_HOLD_MILLIS),
                doubleProperty("facetrack.offaxis.minFrontalQuality", DEFAULT_MIN_FRONTAL_QUALITY)
        );
    }

    private static int intProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double doubleProperty(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
