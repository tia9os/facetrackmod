package com.facetrack.client;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.imageio.ImageIO;

final class SessionRecorder implements AutoCloseable {
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final Path sessionsDirectory;
    private BufferedWriter writer;
    private Path recordingFile;

    SessionRecorder(Path baseDirectory) {
        sessionsDirectory = baseDirectory.resolve("sessions");
    }

    boolean isRecording() {
        return writer != null;
    }

    Path currentFile() {
        return recordingFile;
    }

    Path start() throws IOException {
        Files.createDirectories(sessionsDirectory);
        recordingFile = sessionsDirectory.resolve("expressions-" + FILE_STAMP.format(Instant.now()) + ".csv");
        writer = Files.newBufferedWriter(recordingFile);
        writer.write("timestamp,expression,confidence,face_count,face_x,face_y,face_width,face_height,eyes,smiles,smile_score,mouth_open_score,mouth_wide_score,sad_score,blink_score,wink_score,mouth,left_eye,right_eye,left_eyebrow,right_eyebrow,fps,frontal_quality");
        writer.newLine();
        return recordingFile;
    }

    void record(ExpressionEstimate estimate) {
        if (writer == null) {
            return;
        }

        try {
            FaceParts parts = estimate.parts() == null ? FaceParts.DEFAULT : estimate.parts();
            writer.write(String.format(Locale.US,
                    "%s,%s,%.4f,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s,%s,%.2f,%.4f",
                    estimate.timestamp(),
                    csv(estimate.expression()),
                    estimate.confidence(),
                    estimate.faceCount(),
                    estimate.face().x(),
                    estimate.face().y(),
                    estimate.face().width(),
                    estimate.face().height(),
                    estimate.eyeCount(),
                    estimate.smileCount(),
                    estimate.smileScore(),
                    estimate.mouthOpenScore(),
                    estimate.mouthWideScore(),
                    estimate.sadScore(),
                    estimate.blinkScore(),
                    estimate.winkScore(),
                    csv(parts.mouthWireName()),
                    csv(parts.leftEyeWireName()),
                    csv(parts.rightEyeWireName()),
                    csv(parts.leftEyebrowWireName()),
                    csv(parts.rightEyebrowWireName()),
                    estimate.fps(),
                    estimate.frontalQuality()
            ));
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write expression recording", exception);
        }
    }

    Path saveSnapshot(BufferedImage image) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("No camera frame is available");
        }

        Files.createDirectories(sessionsDirectory);
        Path file = sessionsDirectory.resolve("snapshot-" + FILE_STAMP.format(Instant.now()) + ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    @Override
    public void close() throws IOException {
        if (writer == null) {
            return;
        }

        try {
            writer.flush();
        } finally {
            writer.close();
            writer = null;
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
