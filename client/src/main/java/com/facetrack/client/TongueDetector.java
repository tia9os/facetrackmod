package com.facetrack.client;

import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

final class TongueDetector {
    private TongueDetector() {
    }

    static double score(Mat bgrFrame, Rect requestedRegion) {
        if (bgrFrame == null || bgrFrame.empty() || requestedRegion == null) {
            return 0.0;
        }

        int x = clamp(requestedRegion.x(), 0, Math.max(0, bgrFrame.cols() - 1));
        int y = clamp(requestedRegion.y(), 0, Math.max(0, bgrFrame.rows() - 1));
        int right = clamp(requestedRegion.x() + requestedRegion.width(), x, bgrFrame.cols());
        int bottom = clamp(requestedRegion.y() + requestedRegion.height(), y, bgrFrame.rows());
        if (right - x < 8 || bottom - y < 8) {
            return 0.0;
        }

        Mat region = new Mat(bgrFrame, new Rect(x, y, right - x, bottom - y));
        try {
            return score(region);
        } finally {
            region.close();
        }
    }

    static double score(Mat bgrMouthRegion) {
        if (bgrMouthRegion == null || bgrMouthRegion.empty() || bgrMouthRegion.channels() < 3) {
            return 0.0;
        }

        int width = bgrMouthRegion.cols();
        int height = bgrMouthRegion.rows();
        if (width < 8 || height < 8) {
            return 0.0;
        }

        int minX = width * 24 / 100;
        int maxX = Math.max(minX + 1, width * 76 / 100);
        int minY = height * 38 / 100;
        int maxY = Math.max(minY + 1, height * 96 / 100);
        int total = Math.max(1, (maxX - minX) * (maxY - minY));
        int pinkPixels = 0;
        int strongPinkPixels = 0;
        int clusterMinX = width;
        int clusterMinY = height;
        int clusterMaxX = 0;
        int clusterMaxY = 0;

        UByteIndexer pixels = bgrMouthRegion.createIndexer();
        try {
            for (int y = minY; y < maxY; y++) {
                for (int x = minX; x < maxX; x++) {
                    int blue = pixels.get(y, x, 0);
                    int green = pixels.get(y, x, 1);
                    int red = pixels.get(y, x, 2);
                    int maximum = Math.max(red, Math.max(green, blue));
                    int minimum = Math.min(red, Math.min(green, blue));
                    double saturation = maximum <= 0 ? 0.0 : (double) (maximum - minimum) / maximum;

                    boolean redDominant = red >= 105 && red >= green + 30 && red >= blue + 6 && saturation >= 0.28;
                    boolean pinkDominant = red >= 125 && blue >= 54 && red >= green + 24 && blue >= green - 6 && saturation >= 0.25;
                    boolean skinLike = red >= 116 && green >= 70 && blue >= 44 && red - green < 48 && green - blue < 58;
                    boolean glareLike = red >= 214 && green >= 188 && blue >= 176 && saturation < 0.22;
                    if ((redDominant || pinkDominant) && !skinLike && !glareLike) {
                        pinkPixels++;
                        if (red >= green + 42 && red >= 128 && saturation >= 0.34) {
                            strongPinkPixels++;
                        }
                        clusterMinX = Math.min(clusterMinX, x);
                        clusterMinY = Math.min(clusterMinY, y);
                        clusterMaxX = Math.max(clusterMaxX, x);
                        clusterMaxY = Math.max(clusterMaxY, y);
                    }
                }
            }
        } finally {
            pixels.release();
        }

        if (pinkPixels < 4) {
            return 0.0;
        }

        double pinkRatio = (double) pinkPixels / total;
        double strongRatio = (double) strongPinkPixels / total;
        double clusterWidth = (double) (clusterMaxX - clusterMinX + 1) / width;
        double clusterHeight = (double) (clusterMaxY - clusterMinY + 1) / height;
        double clusterDensity = (double) pinkPixels
                / Math.max(1.0, (clusterMaxX - clusterMinX + 1.0) * (clusterMaxY - clusterMinY + 1.0));
        double lowerReach = (double) (clusterMaxY - minY) / Math.max(1, maxY - minY);
        if (pinkRatio < 0.025
                || strongRatio < 0.010
                || clusterWidth < 0.08
                || clusterHeight < 0.13
                || clusterDensity < 0.07
                || lowerReach < 0.46
                || (clusterWidth > 0.62 && clusterHeight < 0.24)) {
            return 0.0;
        }

        return clamp(
                ((pinkRatio - 0.025) * 8.0)
                        + ((strongRatio - 0.010) * 12.0)
                        + ((clusterWidth - 0.12) * 0.45)
                        + ((clusterHeight - 0.14) * 0.70)
                        + ((clusterDensity - 0.10) * 0.50)
                        + ((lowerReach - 0.55) * 0.35),
                0.0,
                1.0
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
