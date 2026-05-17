package com.facetrack.client;

import java.util.Map;

record CalibrationProfile(Baseline baseline, Map<String, Expression> expressions) {
    CalibrationProfile {
        expressions = Map.copyOf(expressions);
    }

    int readyExpressionCount() {
        int ready = 0;
        for (Expression expression : expressions.values()) {
            if (expression.ready()) {
                ready++;
            }
        }
        return ready;
    }

    record Baseline(
            int targetFrames,
            int samples,
            double smileScore,
            double openScore,
            double wideScore,
            double sadScore
    ) {
        boolean ready() {
            return samples >= Math.min(12, Math.max(1, targetFrames));
        }
    }

    record Expression(
            int targetFrames,
            int samples,
            double smileScore,
            double openScore,
            double wideScore,
            double sadScore,
            double mouthActivityScore,
            double bothEyesScore,
            double oneEyeScore,
            double noEyesScore,
            double blinkShapeScore,
            double winkShapeScore,
            double browRaiseScore,
            double browFurrowScore,
            double headYawScore,
            double expressionScore
    ) {
        boolean ready() {
            return samples >= Math.min(12, Math.max(1, targetFrames));
        }
    }
}
