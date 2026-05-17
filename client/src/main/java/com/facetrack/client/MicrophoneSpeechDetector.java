package com.facetrack.client;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

final class MicrophoneSpeechDetector implements Closeable {
    private static final AudioFormat FORMAT = new AudioFormat(16_000.0F, 16, 1, true, false);
    private static final int CHUNK_MILLIS = 40;
    private static final int HOLD_MILLIS = 280;
    static final String DEFAULT_DEVICE_ID = "default";
    static final int DEFAULT_SENSITIVITY_PERCENT = 50;
    static final int MIN_AMPLIFICATION_PERCENT = 100;
    static final int MAX_AMPLIFICATION_PERCENT = 500;
    static final int DEFAULT_AMPLIFICATION_PERCENT = 100;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile TargetDataLine line;
    private volatile SpeechSample latest = SpeechSample.disabled();
    private volatile String status = "Disabled";
    private volatile int sensitivityPercent = DEFAULT_SENSITIVITY_PERCENT;
    private volatile int amplificationPercent = DEFAULT_AMPLIFICATION_PERCENT;

    void start(String deviceId, int sensitivityPercent) {
        start(deviceId, sensitivityPercent, amplificationPercent);
    }

    void start(String deviceId, int sensitivityPercent, int amplificationPercent) {
        setSensitivityPercent(sensitivityPercent);
        setAmplificationPercent(amplificationPercent);
        if (running) {
            return;
        }

        running = true;
        status = "Starting";
        latest = SpeechSample.unavailable("Starting");
        String selectedDeviceId = normalizeDeviceId(deviceId);
        Thread createdThread = new Thread(() -> captureLoop(selectedDeviceId), "face-expression-microphone");
        createdThread.setDaemon(true);
        thread = createdThread;
        createdThread.start();
    }

    void stop() {
        running = false;
        TargetDataLine currentLine = line;
        if (currentLine != null) {
            currentLine.stop();
            currentLine.close();
        }
        line = null;
        status = "Disabled";
        latest = SpeechSample.disabled();
    }

    SpeechSample sample() {
        return latest;
    }

    String status() {
        return status;
    }

    void setSensitivityPercent(int sensitivityPercent) {
        this.sensitivityPercent = clampPercent(sensitivityPercent);
    }

    void setAmplificationPercent(int amplificationPercent) {
        this.amplificationPercent = clampAmplificationPercent(amplificationPercent);
    }

    static List<MicrophoneDevice> availableDevices() {
        List<MicrophoneDevice> devices = new ArrayList<>();
        devices.add(defaultDevice());

        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(lineInfo)) {
                devices.add(new MicrophoneDevice(deviceId(mixerInfo), mixerLabel(mixerInfo)));
            }
        }
        return List.copyOf(devices);
    }

    static MicrophoneDevice defaultDevice() {
        return new MicrophoneDevice(DEFAULT_DEVICE_ID, "Default microphone");
    }

    static MicrophoneDevice deviceForId(String deviceId) {
        String normalized = normalizeDeviceId(deviceId);
        for (MicrophoneDevice device : availableDevices()) {
            if (device.id().equals(normalized)) {
                return device;
            }
        }
        return defaultDevice();
    }

    private void captureLoop(String deviceId) {
        TargetDataLine openedLine = null;
        try {
            openedLine = openLine(deviceId);
            int chunkBytes = Math.max(1, (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() * CHUNK_MILLIS / 1000.0));
            openedLine.open(FORMAT, chunkBytes * 4);
            openedLine.start();
            line = openedLine;

            byte[] buffer = new byte[chunkBytes];
            double noiseFloor = 0.012;
            int speechFrames = 0;
            long lastSpeechMillis = 0L;
            status = "Listening";

            while (running) {
                int bytesRead = openedLine.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    continue;
                }

                double level = amplifiedLevel(rms(buffer, bytesRead), amplificationPercent);
                double threshold = speechThreshold(noiseFloor, sensitivityPercent);
                boolean speechFrame = level >= threshold;
                if (speechFrame) {
                    speechFrames = Math.min(8, speechFrames + 1);
                    lastSpeechMillis = System.currentTimeMillis();
                } else {
                    speechFrames = Math.max(0, speechFrames - 1);
                    double adaptationWeight = level < noiseFloor ? 0.08 : 0.025;
                    noiseFloor = (noiseFloor * (1.0 - adaptationWeight)) + (level * adaptationWeight);
                }
                noiseFloor = clamp(noiseFloor, 0.002, 0.14);

                long now = System.currentTimeMillis();
                boolean talking = speechFrames >= 2 || now - lastSpeechMillis <= HOLD_MILLIS;
                double confidence = talking ? clamp((level - threshold) / Math.max(0.012, threshold), 0.18, 1.0) : 0.0;
                latest = new SpeechSample(true, true, talking, confidence, level, noiseFloor, threshold, statusText(talking, level));
                status = latest.status();
            }
        } catch (Exception exception) {
            status = "Mic unavailable";
            latest = SpeechSample.unavailable(status);
        } finally {
            if (openedLine != null) {
                openedLine.stop();
                openedLine.close();
            }
            line = null;
            if (!running) {
                status = "Disabled";
                latest = SpeechSample.disabled();
            }
            running = false;
        }
    }

    private static TargetDataLine openLine(String deviceId) throws LineUnavailableException {
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (DEFAULT_DEVICE_ID.equals(normalizeDeviceId(deviceId))) {
            if (!AudioSystem.isLineSupported(lineInfo)) {
                throw new LineUnavailableException("No supported default microphone");
            }
            return (TargetDataLine) AudioSystem.getLine(lineInfo);
        }

        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (!deviceId(mixerInfo).equals(deviceId)) {
                continue;
            }
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (!mixer.isLineSupported(lineInfo)) {
                throw new LineUnavailableException("Selected microphone does not support the required format");
            }
            return (TargetDataLine) mixer.getLine(lineInfo);
        }
        throw new LineUnavailableException("Selected microphone was not found");
    }

    private static double rms(byte[] buffer, int bytesRead) {
        int samples = bytesRead / 2;
        if (samples <= 0) {
            return 0.0;
        }

        double sumSquares = 0.0;
        for (int index = 0; index + 1 < bytesRead; index += 2) {
            int low = buffer[index] & 0xFF;
            int high = buffer[index + 1];
            int sample = (high << 8) | low;
            sumSquares += sample * (double) sample;
        }
        return Math.sqrt(sumSquares / samples) / 32768.0;
    }

    private static String statusText(boolean talking, double level) {
        int percent = (int) Math.round(clamp(level * 500.0, 0.0, 100.0));
        return (talking ? "Talking " : "Silent ") + percent + "%";
    }

    private static double speechThreshold(double noiseFloor, int sensitivityPercent) {
        double sensitivity = clampPercent(sensitivityPercent) / 100.0;
        double curve = Math.pow(sensitivity, 0.72);
        double multiplier = 4.6 - curve * 3.25;
        double offset = 0.038 - curve * 0.034;
        return Math.max(noiseFloor * multiplier, noiseFloor + offset);
    }

    private static double amplifiedLevel(double rawLevel, int amplificationPercent) {
        double gain = clampAmplificationPercent(amplificationPercent) / 100.0;
        return clamp(rawLevel * gain, 0.0, 1.0);
    }

    private static String normalizeDeviceId(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? DEFAULT_DEVICE_ID : deviceId.trim();
    }

    private static String deviceId(Mixer.Info mixerInfo) {
        return mixerInfo.getName() + "|" + mixerInfo.getVendor() + "|" + mixerInfo.getVersion();
    }

    private static String mixerLabel(Mixer.Info mixerInfo) {
        String description = mixerInfo.getDescription();
        if (description == null || description.isBlank() || mixerInfo.getName().equals(description)) {
            return mixerInfo.getName();
        }
        return mixerInfo.getName() + " - " + description;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampAmplificationPercent(int value) {
        return Math.max(MIN_AMPLIFICATION_PERCENT, Math.min(MAX_AMPLIFICATION_PERCENT, value));
    }

    @Override
    public void close() {
        stop();
    }

    record SpeechSample(
            boolean enabled,
            boolean available,
            boolean talking,
            double confidence,
            double level,
            double noiseFloor,
            double threshold,
            String status
    ) {
        static SpeechSample disabled() {
            return new SpeechSample(false, false, false, 0.0, 0.0, 0.0, 0.0, "Disabled");
        }

        static SpeechSample unavailable(String status) {
            return new SpeechSample(true, false, false, 0.0, 0.0, 0.0, 0.0, status);
        }
    }

    record MicrophoneDevice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
