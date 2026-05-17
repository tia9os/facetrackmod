package com.facetrack.client;

import java.awt.image.BufferedImage;

record TrackingFrame(BufferedImage image, ExpressionEstimate estimate) {
}
