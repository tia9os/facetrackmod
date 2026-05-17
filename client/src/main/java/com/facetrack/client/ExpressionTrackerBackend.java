package com.facetrack.client;

import org.bytedeco.opencv.opencv_core.Mat;

interface ExpressionTrackerBackend extends AutoCloseable {
    boolean requestCalibration(String expression);

    String calibrationStatus();

    long calibrationRevision();

    CalibrationProfile calibrationProfile();

    void applyCalibrationProfile(CalibrationProfile profile);

    TrackingFrame track(Mat cameraFrame, double fps, MicrophoneSpeechDetector.SpeechSample speech);

    String runtimeStatus();

    @Override
    default void close() {
    }
}
