package com.facetrack.client;

enum RecognitionQuality {
    FAST("Fast", 960, 540, 1.18, 4, 1.12, 3, 1.45, 24, false, false, false, 0.35),
    BALANCED("Balanced", 1280, 720, 1.10, 4, 1.08, 3, 1.35, 20, true, false, false, 0.45),
    ACCURATE("Accurate", 1920, 1080, 1.06, 5, 1.05, 4, 1.22, 16, true, true, true, 0.65);

    private final String label;
    private final int cameraWidth;
    private final int cameraHeight;
    private final double faceScaleFactor;
    private final int faceMinNeighbors;
    private final double eyeScaleFactor;
    private final int eyeMinNeighbors;
    private final double smileScaleFactor;
    private final int smileMinNeighbors;
    private final boolean claheEnabled;
    private final boolean adaptiveMouthThresholdEnabled;
    private final boolean landmarkRefinementEnabled;
    private final double landmarkBlend;

    RecognitionQuality(
            String label,
            int cameraWidth,
            int cameraHeight,
            double faceScaleFactor,
            int faceMinNeighbors,
            double eyeScaleFactor,
            int eyeMinNeighbors,
            double smileScaleFactor,
            int smileMinNeighbors,
            boolean claheEnabled,
            boolean adaptiveMouthThresholdEnabled,
            boolean landmarkRefinementEnabled,
            double landmarkBlend
    ) {
        this.label = label;
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.faceScaleFactor = faceScaleFactor;
        this.faceMinNeighbors = faceMinNeighbors;
        this.eyeScaleFactor = eyeScaleFactor;
        this.eyeMinNeighbors = eyeMinNeighbors;
        this.smileScaleFactor = smileScaleFactor;
        this.smileMinNeighbors = smileMinNeighbors;
        this.claheEnabled = claheEnabled;
        this.adaptiveMouthThresholdEnabled = adaptiveMouthThresholdEnabled;
        this.landmarkRefinementEnabled = landmarkRefinementEnabled;
        this.landmarkBlend = landmarkBlend;
    }

    int cameraWidth() {
        return cameraWidth;
    }

    int cameraHeight() {
        return cameraHeight;
    }

    double faceScaleFactor() {
        return faceScaleFactor;
    }

    int faceMinNeighbors() {
        return faceMinNeighbors;
    }

    double eyeScaleFactor() {
        return eyeScaleFactor;
    }

    int eyeMinNeighbors() {
        return eyeMinNeighbors;
    }

    double smileScaleFactor() {
        return smileScaleFactor;
    }

    int smileMinNeighbors() {
        return smileMinNeighbors;
    }

    boolean claheEnabled() {
        return claheEnabled;
    }

    boolean adaptiveMouthThresholdEnabled() {
        return adaptiveMouthThresholdEnabled;
    }

    boolean landmarkRefinementEnabled() {
        return landmarkRefinementEnabled;
    }

    double landmarkBlend() {
        return landmarkBlend;
    }

    @Override
    public String toString() {
        return label;
    }
}
