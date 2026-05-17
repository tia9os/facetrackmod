package com.facetrack.client2;

import java.util.Locale;

enum ExpressionLabel {
    NEUTRAL("Neutral"),
    HAPPY("Happy"),
    FUNNY("Funny"),
    SAD("Sad"),
    SURPRISED("Surprised"),
    TALKING("Talking"),
    BLINKING("Blinking"),
    WINKING("Winking"),
    FOCUSED("Focused"),
    NO_FACE("No face");

    static final ExpressionLabel[] TRAINABLE = {
            NEUTRAL,
            HAPPY,
            FUNNY,
            SAD,
            SURPRISED,
            TALKING,
            BLINKING,
            WINKING,
            FOCUSED
    };

    private final String displayName;

    ExpressionLabel(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ExpressionLabel fromDisplayName(String value) {
        if (value != null) {
            for (ExpressionLabel label : values()) {
                if (label.displayName.equalsIgnoreCase(value.trim()) || label.name().equalsIgnoreCase(value.trim())) {
                    return label;
                }
            }
        }
        return NEUTRAL;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
