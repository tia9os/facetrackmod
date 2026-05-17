package com.facetrack.client;

import java.util.Locale;

record FaceParts(
        Mouth mouth,
        Eye leftEye,
        Eye rightEye,
        Eyebrow leftEyebrow,
        Eyebrow rightEyebrow
) {
    static final FaceParts DEFAULT = new FaceParts(Mouth.NEUTRAL, Eye.OPEN, Eye.OPEN, Eyebrow.NEUTRAL, Eyebrow.NEUTRAL);

    FaceParts {
        mouth = mouth == null ? Mouth.NEUTRAL : mouth;
        leftEye = leftEye == null ? Eye.OPEN : leftEye;
        rightEye = rightEye == null ? Eye.OPEN : rightEye;
        leftEyebrow = leftEyebrow == null ? Eyebrow.NEUTRAL : leftEyebrow;
        rightEyebrow = rightEyebrow == null ? Eyebrow.NEUTRAL : rightEyebrow;
    }

    String mouthWireName() {
        return mouth.wireName();
    }

    String leftEyeWireName() {
        return leftEye.wireName();
    }

    String rightEyeWireName() {
        return rightEye.wireName();
    }

    String leftEyebrowWireName() {
        return leftEyebrow.wireName();
    }

    String rightEyebrowWireName() {
        return rightEyebrow.wireName();
    }

    enum Mouth {
        NEUTRAL,
        HAPPY,
        SAD,
        SURPRISED,
        TALKING,
        FUNNY;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Eye {
        OPEN,
        CLOSED,
        FOCUSED;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Eyebrow {
        NEUTRAL,
        RAISED,
        SAD,
        FOCUSED;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
