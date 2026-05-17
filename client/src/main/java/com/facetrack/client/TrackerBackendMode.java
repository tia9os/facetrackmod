package com.facetrack.client;

enum TrackerBackendMode {
    OPENCV("OpenCV"),
    MODERN_ONNX("Modern ONNX");

    private final String label;

    TrackerBackendMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
