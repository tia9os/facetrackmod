package com.facetrack.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

final class ClientSettingsStore {
    private static final String FILE_NAME = "settings.properties";
    static final String ONNX_MODEL_PROPERTY = "facetrack.onnx.model";
    static final String LANDMARK_MODEL_PROPERTY = "facetrack.landmark.model";
    private static final ClientSettings DEFAULT = new ClientSettings(
            "default",
            0,
            TrackerBackendMode.OPENCV,
            RecognitionQuality.BALANCED,
            HardwareAccelerationMode.AUTO,
            defaultOpenCvThreads(),
            OffAxisMode.HOLD_LAST,
            700,
            45,
            false,
            MicrophoneSpeechDetector.DEFAULT_DEVICE_ID,
            MicrophoneSpeechDetector.DEFAULT_SENSITIVITY_PERCENT,
            MicrophoneSpeechDetector.DEFAULT_AMPLIFICATION_PERCENT,
            "Neutral",
            "",
            "",
            -1,
            -1,
            1120,
            680,
            false
    );

    private final Path settingsFile;

    ClientSettingsStore(Path baseDirectory) {
        settingsFile = baseDirectory.resolve(FILE_NAME);
    }

    ClientSettings load() throws IOException {
        if (!Files.isRegularFile(settingsFile)) {
            return DEFAULT;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(settingsFile)) {
            properties.load(reader);
        }

        return new ClientSettings(
                stringValue(properties, "profile.name", DEFAULT.profileName()),
                intValue(properties, "camera.index", DEFAULT.cameraIndex(), 0, 16),
                enumValue(properties, "tracker.backend", TrackerBackendMode.class, DEFAULT.trackerBackend()),
                enumValue(properties, "recognition.quality", RecognitionQuality.class, DEFAULT.quality()),
                enumValue(properties, "hardware.acceleration", HardwareAccelerationMode.class, DEFAULT.accelerationMode()),
                intValue(properties, "opencv.threads", DEFAULT.openCvThreads(), 1, 32),
                OffAxisMode.fromProperty(properties.getProperty("offaxis.mode"), DEFAULT.offAxisMode()),
                intValue(properties, "offaxis.holdMillis", DEFAULT.offAxisHoldMillis(), 0, 5000),
                intValue(properties, "offaxis.minFrontalQualityPercent", DEFAULT.minFrontalQualityPercent(), 0, 100),
                booleanValue(properties, "microphone.speech.enabled", DEFAULT.microphoneSpeechEnabled()),
                stringValue(properties, "microphone.device", DEFAULT.microphoneDeviceId()),
                intValue(properties, "microphone.sensitivityPercent", DEFAULT.microphoneSensitivityPercent(), 0, 100),
                intValue(properties, "microphone.amplificationPercent", DEFAULT.microphoneAmplificationPercent(),
                        MicrophoneSpeechDetector.MIN_AMPLIFICATION_PERCENT,
                        MicrophoneSpeechDetector.MAX_AMPLIFICATION_PERCENT),
                stringValue(properties, "calibration.expression", DEFAULT.calibrationExpression()),
                stringValue(properties, ONNX_MODEL_PROPERTY, DEFAULT.onnxModelPath()),
                stringValue(properties, LANDMARK_MODEL_PROPERTY, DEFAULT.landmarkModelPath()),
                intValue(properties, "window.x", DEFAULT.windowX(), -100000, 100000),
                intValue(properties, "window.y", DEFAULT.windowY(), -100000, 100000),
                intValue(properties, "window.width", DEFAULT.windowWidth(), 760, 100000),
                intValue(properties, "window.height", DEFAULT.windowHeight(), 560, 100000),
                booleanValue(properties, "window.maximized", DEFAULT.windowMaximized())
        );
    }

    void save(ClientSettings settings) throws IOException {
        Files.createDirectories(settingsFile.getParent());

        ClientSettings value = settings == null ? DEFAULT : settings;
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("profile.name", value.profileName());
        properties.setProperty("camera.index", Integer.toString(value.cameraIndex()));
        properties.setProperty("tracker.backend", value.trackerBackend().name());
        properties.setProperty("recognition.quality", value.quality().name());
        properties.setProperty("hardware.acceleration", value.accelerationMode().name());
        properties.setProperty("opencv.threads", Integer.toString(value.openCvThreads()));
        properties.setProperty("offaxis.mode", value.offAxisMode().name());
        properties.setProperty("offaxis.holdMillis", Integer.toString(value.offAxisHoldMillis()));
        properties.setProperty("offaxis.minFrontalQualityPercent", Integer.toString(value.minFrontalQualityPercent()));
        properties.setProperty("microphone.speech.enabled", Boolean.toString(value.microphoneSpeechEnabled()));
        properties.setProperty("microphone.device", value.microphoneDeviceId());
        properties.setProperty("microphone.sensitivityPercent", Integer.toString(value.microphoneSensitivityPercent()));
        properties.setProperty("microphone.amplificationPercent", Integer.toString(value.microphoneAmplificationPercent()));
        properties.setProperty("calibration.expression", value.calibrationExpression());
        properties.setProperty(ONNX_MODEL_PROPERTY, value.onnxModelPath());
        properties.setProperty(LANDMARK_MODEL_PROPERTY, value.landmarkModelPath());
        properties.setProperty("window.x", Integer.toString(value.windowX()));
        properties.setProperty("window.y", Integer.toString(value.windowY()));
        properties.setProperty("window.width", Integer.toString(value.windowWidth()));
        properties.setProperty("window.height", Integer.toString(value.windowHeight()));
        properties.setProperty("window.maximized", Boolean.toString(value.windowMaximized()));

        try (Writer writer = Files.newBufferedWriter(settingsFile)) {
            properties.store(writer, "Face Expression Client settings");
        }
    }

    Path settingsFile() {
        return settingsFile;
    }

    static ClientSettings defaults() {
        return DEFAULT;
    }

    private static String stringValue(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intValue(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static <T extends Enum<T>> T enumValue(Properties properties, String key, Class<T> type, T fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static int defaultOpenCvThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    record ClientSettings(
            String profileName,
            int cameraIndex,
            TrackerBackendMode trackerBackend,
            RecognitionQuality quality,
            HardwareAccelerationMode accelerationMode,
            int openCvThreads,
            OffAxisMode offAxisMode,
            int offAxisHoldMillis,
            int minFrontalQualityPercent,
            boolean microphoneSpeechEnabled,
            String microphoneDeviceId,
            int microphoneSensitivityPercent,
            int microphoneAmplificationPercent,
            String calibrationExpression,
            String onnxModelPath,
            String landmarkModelPath,
            int windowX,
            int windowY,
            int windowWidth,
            int windowHeight,
            boolean windowMaximized
    ) {
        ClientSettings {
            profileName = profileName == null || profileName.isBlank() ? "default" : profileName.trim();
            cameraIndex = Math.max(0, Math.min(16, cameraIndex));
            trackerBackend = trackerBackend == null ? TrackerBackendMode.OPENCV : trackerBackend;
            quality = quality == null ? RecognitionQuality.BALANCED : quality;
            accelerationMode = accelerationMode == null ? HardwareAccelerationMode.AUTO : accelerationMode;
            openCvThreads = Math.max(1, Math.min(32, openCvThreads));
            offAxisMode = offAxisMode == null ? OffAxisMode.HOLD_LAST : offAxisMode;
            offAxisHoldMillis = Math.max(0, Math.min(5000, offAxisHoldMillis));
            minFrontalQualityPercent = Math.max(0, Math.min(100, minFrontalQualityPercent));
            microphoneDeviceId = microphoneDeviceId == null || microphoneDeviceId.isBlank()
                    ? MicrophoneSpeechDetector.DEFAULT_DEVICE_ID
                    : microphoneDeviceId.trim();
            microphoneSensitivityPercent = Math.max(0, Math.min(100, microphoneSensitivityPercent));
            microphoneAmplificationPercent = Math.max(
                    MicrophoneSpeechDetector.MIN_AMPLIFICATION_PERCENT,
                    Math.min(MicrophoneSpeechDetector.MAX_AMPLIFICATION_PERCENT, microphoneAmplificationPercent)
            );
            calibrationExpression = calibrationExpression == null || calibrationExpression.isBlank()
                    ? "Neutral"
                    : calibrationExpression.trim();
            onnxModelPath = normalizeOptionalPath(onnxModelPath);
            landmarkModelPath = normalizeOptionalPath(landmarkModelPath);
            windowWidth = Math.max(760, windowWidth);
            windowHeight = Math.max(560, windowHeight);
        }

        boolean hasWindowLocation() {
            return windowX >= 0 && windowY >= 0;
        }

        private static String normalizeOptionalPath(String path) {
            return path == null || path.isBlank() ? "" : path.trim();
        }
    }
}
