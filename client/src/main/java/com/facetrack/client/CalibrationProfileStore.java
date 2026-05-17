package com.facetrack.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class CalibrationProfileStore {
    private static final String LEGACY_PROFILE_FILE = "default.properties";

    private final Path profilesDirectory;

    CalibrationProfileStore(Path baseDirectory) {
        profilesDirectory = baseDirectory.resolve("profiles");
    }

    Path profileFile(ProfileKey key) {
        return profilesDirectory.resolve(key.fileName());
    }

    CalibrationProfile load(ProfileKey key) throws IOException {
        Path file = existingProfileFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }

        CalibrationProfile.Baseline baseline = new CalibrationProfile.Baseline(
                intValue(properties, "baseline.targetFrames", 0),
                intValue(properties, "baseline.samples", 0),
                doubleValue(properties, "baseline.smileScore"),
                doubleValue(properties, "baseline.openScore"),
                doubleValue(properties, "baseline.wideScore"),
                doubleValue(properties, "baseline.sadScore")
        );

        Map<String, CalibrationProfile.Expression> expressions = new LinkedHashMap<>();
        for (String expression : FaceExpressionTracker.calibratableExpressions()) {
            String prefix = "expression." + key(expression) + ".";
            int samples = intValue(properties, prefix + "samples", 0);
            if (samples <= 0) {
                continue;
            }

            expressions.put(expression, new CalibrationProfile.Expression(
                    intValue(properties, prefix + "targetFrames", 0),
                    samples,
                    doubleValue(properties, prefix + "smileScore"),
                    doubleValue(properties, prefix + "openScore"),
                    doubleValue(properties, prefix + "wideScore"),
                    doubleValue(properties, prefix + "sadScore"),
                    doubleValue(properties, prefix + "mouthActivityScore"),
                    doubleValue(properties, prefix + "bothEyesScore"),
                    doubleValue(properties, prefix + "oneEyeScore"),
                    doubleValue(properties, prefix + "noEyesScore"),
                    doubleValue(properties, prefix + "blinkShapeScore"),
                    doubleValue(properties, prefix + "winkShapeScore"),
                    doubleValue(properties, prefix + "browRaiseScore"),
                    doubleValue(properties, prefix + "browFurrowScore"),
                    doubleValue(properties, prefix + "headYawScore"),
                    doubleValue(properties, prefix + "expressionScore")
            ));
        }

        if (baseline.samples() <= 0 && expressions.isEmpty()) {
            return null;
        }
        return new CalibrationProfile(baseline, expressions);
    }

    Path save(ProfileKey key, CalibrationProfile profile) throws IOException {
        Files.createDirectories(profilesDirectory);
        Path file = profileFile(key);

        Properties properties = new Properties();
        properties.setProperty("version", "2");
        properties.setProperty("profile.name", key.name());
        properties.setProperty("profile.cameraIndex", Integer.toString(key.cameraIndex()));
        properties.setProperty("profile.quality", key.quality().name());
        putBaseline(properties, profile.baseline());
        for (Map.Entry<String, CalibrationProfile.Expression> entry : profile.expressions().entrySet()) {
            putExpression(properties, entry.getKey(), entry.getValue());
        }

        try (Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, "Face Expression Client calibration profile");
        }
        return file;
    }

    private static void putBaseline(Properties properties, CalibrationProfile.Baseline baseline) {
        properties.setProperty("baseline.targetFrames", Integer.toString(baseline.targetFrames()));
        properties.setProperty("baseline.samples", Integer.toString(baseline.samples()));
        properties.setProperty("baseline.smileScore", format(baseline.smileScore()));
        properties.setProperty("baseline.openScore", format(baseline.openScore()));
        properties.setProperty("baseline.wideScore", format(baseline.wideScore()));
        properties.setProperty("baseline.sadScore", format(baseline.sadScore()));
    }

    private static void putExpression(Properties properties, String expression, CalibrationProfile.Expression profile) {
        if (profile.samples() <= 0) {
            return;
        }

        String prefix = "expression." + key(expression) + ".";
        properties.setProperty(prefix + "targetFrames", Integer.toString(profile.targetFrames()));
        properties.setProperty(prefix + "samples", Integer.toString(profile.samples()));
        properties.setProperty(prefix + "smileScore", format(profile.smileScore()));
        properties.setProperty(prefix + "openScore", format(profile.openScore()));
        properties.setProperty(prefix + "wideScore", format(profile.wideScore()));
        properties.setProperty(prefix + "sadScore", format(profile.sadScore()));
        properties.setProperty(prefix + "mouthActivityScore", format(profile.mouthActivityScore()));
        properties.setProperty(prefix + "bothEyesScore", format(profile.bothEyesScore()));
        properties.setProperty(prefix + "oneEyeScore", format(profile.oneEyeScore()));
        properties.setProperty(prefix + "noEyesScore", format(profile.noEyesScore()));
        properties.setProperty(prefix + "blinkShapeScore", format(profile.blinkShapeScore()));
        properties.setProperty(prefix + "winkShapeScore", format(profile.winkShapeScore()));
        properties.setProperty(prefix + "browRaiseScore", format(profile.browRaiseScore()));
        properties.setProperty(prefix + "browFurrowScore", format(profile.browFurrowScore()));
        properties.setProperty(prefix + "headYawScore", format(profile.headYawScore()));
        properties.setProperty(prefix + "expressionScore", format(profile.expressionScore()));
    }

    private Path existingProfileFile(ProfileKey key) {
        Path scopedFile = profileFile(key);
        if (Files.isRegularFile(scopedFile) || !key.legacyDefault()) {
            return scopedFile;
        }
        return profilesDirectory.resolve(LEGACY_PROFILE_FILE);
    }

    private static String key(String expression) {
        return expression.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static int intValue(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double doubleValue(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    record ProfileKey(String name, int cameraIndex, RecognitionQuality quality) {
        ProfileKey {
            name = normalizeProfileName(name);
            cameraIndex = Math.max(0, cameraIndex);
            quality = quality == null ? RecognitionQuality.BALANCED : quality;
        }

        String displayName() {
            return name + ", camera " + cameraIndex + ", " + quality;
        }

        private String fileName() {
            return safeFileComponent(name) + "-camera" + cameraIndex + "-"
                    + quality.name().toLowerCase(Locale.ROOT) + ".properties";
        }

        private boolean legacyDefault() {
            return "default".equalsIgnoreCase(name) && cameraIndex == 0 && quality == RecognitionQuality.BALANCED;
        }
    }

    private static String normalizeProfileName(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        return name.trim();
    }

    private static String safeFileComponent(String value) {
        String normalized = normalizeProfileName(value);
        StringBuilder safe = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.'
                    || character == '_'
                    || character == '-') {
                safe.append(character);
            } else {
                safe.append('_');
            }
        }
        return safe.length() == 0 ? "default" : safe.toString();
    }
}
