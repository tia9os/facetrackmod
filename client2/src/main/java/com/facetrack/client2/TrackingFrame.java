package com.facetrack.client2;

import java.awt.image.BufferedImage;

record TrackingFrame(BufferedImage image, ExpressionEstimate estimate) {
}
