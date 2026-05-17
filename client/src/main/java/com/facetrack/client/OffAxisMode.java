package com.facetrack.client;

import java.util.Locale;

enum OffAxisMode {
    HOLD_LAST("Hold last"),
    NEUTRAL("Neutral"),
    NO_FACE("No face");

    private final String label;

    OffAxisMode(String label) {
        this.label = label;
    }

    static OffAxisMode fromProperty(String value, OffAxisMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (OffAxisMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return label;
    }
}
