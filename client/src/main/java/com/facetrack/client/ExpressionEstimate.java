package com.facetrack.client;

import java.time.Instant;
import org.bytedeco.opencv.opencv_core.Rect;

record ExpressionEstimate(
        Instant timestamp,
        String expression,
        double confidence,
        int faceCount,
        Rect face,
        int eyeCount,
        int smileCount,
        double smileScore,
        double mouthOpenScore,
        double mouthWideScore,
        double sadScore,
        double blinkScore,
        double winkScore,
        FaceParts parts,
        double fps,
        double frontalQuality
) {
    static ExpressionEstimate noFace(Instant timestamp, double fps) {
        return new ExpressionEstimate(
                timestamp,
                "No face",
                0.0,
                0,
                new Rect(0, 0, 0, 0),
                0,
                0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                FaceParts.DEFAULT,
                fps,
                0.0
        );
    }

    ExpressionEstimate withMicrophoneSpeech(MicrophoneSpeechDetector.SpeechSample speech) {
        if (speech == null || !speech.enabled() || !speech.available()) {
            return this;
        }
        if (faceCount <= 0) {
            return this;
        }

        if (speech.talking()) {
            if ("Blinking".equals(expression) || "Winking".equals(expression)) {
                return this;
            }
            if ("Funny".equals(expression)) {
                return this;
            }
            if (isPersistentExpression(expression)) {
                return withExpression(
                        expression,
                        confidence,
                        partsWithMouth(FaceParts.Mouth.TALKING)
                );
            }
            return withExpression(
                    "Talking",
                    Math.max(confidence, Math.min(0.94, Math.max(0.42, speech.confidence() * 0.88))),
                    partsWithMouth(FaceParts.Mouth.TALKING)
            );
        }

        if ("Talking".equals(expression)) {
            return withExpression(
                    "Neutral",
                    Math.min(confidence, 0.44),
                    partsWithMouth(FaceParts.Mouth.NEUTRAL)
            );
        }
        return this;
    }

    private static boolean isPersistentExpression(String expression) {
        return "Happy".equals(expression)
                || "Sad".equals(expression)
                || "Surprised".equals(expression)
                || "Funny".equals(expression)
                || "Focused".equals(expression);
    }

    private ExpressionEstimate withExpression(String newExpression, double newConfidence, FaceParts newParts) {
        return new ExpressionEstimate(
                timestamp,
                newExpression,
                newConfidence,
                faceCount,
                face,
                eyeCount,
                smileCount,
                smileScore,
                mouthOpenScore,
                mouthWideScore,
                sadScore,
                blinkScore,
                winkScore,
                newParts,
                fps,
                frontalQuality
        );
    }

    private FaceParts partsWithMouth(FaceParts.Mouth mouth) {
        FaceParts source = parts == null ? FaceParts.DEFAULT : parts;
        return new FaceParts(
                mouth,
                source.leftEye(),
                source.rightEye(),
                source.leftEyebrow(),
                source.rightEyebrow()
        );
    }
}
