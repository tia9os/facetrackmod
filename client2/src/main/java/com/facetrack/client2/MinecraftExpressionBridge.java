package com.facetrack.client2;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class MinecraftExpressionBridge implements Closeable {
    private static final int DEFAULT_PORT = 34321;
    private static final long SEND_INTERVAL_NANOS = 100_000_000L;

    private final int port = Integer.getInteger("facetrack.bridge.port", DEFAULT_PORT);
    private final InetAddress address;
    private final DatagramSocket socket;
    private volatile String status;
    private long lastSentNanos;

    MinecraftExpressionBridge() {
        DatagramSocket createdSocket = null;
        InetAddress createdAddress = InetAddress.getLoopbackAddress();
        try {
            createdAddress = InetAddress.getByName("127.0.0.1");
            createdSocket = new DatagramSocket();
            status = "UDP 127.0.0.1:" + port;
        } catch (IOException exception) {
            status = "Bridge unavailable: " + exception.getMessage();
        }
        address = createdAddress;
        socket = createdSocket;
    }

    String status() {
        return status;
    }

    void send(ExpressionEstimate estimate) {
        if (socket == null || estimate == null) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastSentNanos < SEND_INTERVAL_NANOS) {
            return;
        }

        byte[] payload = encode(estimate).getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, payload.length, address, port);
        try {
            socket.send(packet);
            lastSentNanos = now;
            status = "Sending to Minecraft";
        } catch (IOException exception) {
            status = "Bridge error: " + exception.getMessage();
        }
    }

    private static String encode(ExpressionEstimate estimate) {
        FaceParts parts = estimate.parts() == null ? FaceParts.DEFAULT : estimate.parts();
        return String.format(Locale.US,
                "facetrack_parts|1|%d|%s|%.4f|%d|%.4f|%.4f|%.4f|%.4f|%.4f|%.4f|%.2f|%s|%s|%s|%s|%s",
                estimate.timestamp().toEpochMilli(),
                estimate.faceCount() <= 0 ? "no_face" : estimate.expression().wireName(),
                estimate.confidence(),
                estimate.faceCount(),
                estimate.smileScore(),
                estimate.mouthOpenScore(),
                estimate.mouthWideScore(),
                estimate.sadScore(),
                estimate.blinkScore(),
                estimate.winkScore(),
                estimate.fps(),
                parts.mouthWireName(),
                parts.leftEyeWireName(),
                parts.rightEyeWireName(),
                parts.leftEyebrowWireName(),
                parts.rightEyebrowWireName()
        );
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
    }
}
