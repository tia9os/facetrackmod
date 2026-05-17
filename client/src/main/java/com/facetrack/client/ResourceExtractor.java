package com.facetrack.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ResourceExtractor {
    private ResourceExtractor() {
    }

    static Path extractCascade(String resourceName) {
        String resourcePath = "/cascades/" + resourceName;

        try (InputStream stream = ResourceExtractor.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled cascade resource: " + resourcePath);
            }

            Path directory = Files.createTempDirectory("face-expression-cascades-");
            directory.toFile().deleteOnExit();

            Path file = directory.resolve(resourceName);
            Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to extract cascade resource: " + resourcePath, exception);
        }
    }
}
