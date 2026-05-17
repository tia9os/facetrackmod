package com.facetrack.client2;

import java.time.Instant;
import org.bytedeco.opencv.opencv_core.Rect;

record ExpressionEstimate(
        Instant timestamp,
        ExpressionLabel expression,
        double confidence,
        int faceCount,
        Rect face,
        double smileScore,
        double mouthOpenScore,
        double mouthWideScore,
        double sadScore,
        double blinkScore,
        double winkScore,
        FaceParts parts,
        double fps,
        double frontalQuality,
        ExpressionFeatures features
) {
    static ExpressionEstimate noFace(double fps) {
        return new ExpressionEstimate(
                Instant.now(),
                ExpressionLabel.NO_FACE,
                0.0,
                0,
                new Rect(0, 0, 0, 0),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                FaceParts.DEFAULT,
                fps,
                0.0,
                null
        );
    }
}
