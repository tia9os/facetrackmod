package com.facetrack.client2;

import java.util.Locale;

record ExpressionFeatures(
        double smile,
        double mouthOpen,
        double mouthWide,
        double sad,
        double mouthActivity,
        double eyePresence,
        double blink,
        double wink,
        double leftClosed,
        double rightClosed,
        double faceSize,
        double faceAspect,
        double centerOffset,
        double frontalQuality
) {
    static final int SIZE = 14;

    double[] toArray() {
        return new double[] {
                clamp01(smile),
                clamp01(mouthOpen),
                clamp01(mouthWide),
                clamp01(sad),
                clamp01(mouthActivity),
                clamp01(eyePresence),
                clamp01(blink),
                clamp01(wink),
                clamp01(leftClosed),
                clamp01(rightClosed),
                clamp01(faceSize),
                clamp01(faceAspect),
                clamp01(centerOffset),
                clamp01(frontalQuality)
        };
    }

    String compactText() {
        return String.format(Locale.US,
                "smile %.2f open %.2f activity %.2f blink %.2f wink %.2f q %.2f",
                smile,
                mouthOpen,
                mouthActivity,
                blink,
                wink,
                frontalQuality);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
