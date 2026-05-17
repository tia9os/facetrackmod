package com.testmod.client;

import com.testmod.TestMod;
import com.testmod.expression.ExpressionKind;
import com.testmod.expression.ExpressionParts;
import com.testmod.expression.ExpressionSnapshot;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

final class LocalExpressionReceiver implements AutoCloseable {
	static final int DEFAULT_PORT = 34321;
	private static final int MAX_PACKET_BYTES = 8192;

	private final int port;
	private final AtomicReference<ReceivedExpression> latest = new AtomicReference<>();
	private volatile boolean running;
	private volatile DatagramSocket socket;
	private volatile ExpressionListener expressionListener;
	private Thread thread;

	LocalExpressionReceiver(int port) {
		this.port = port;
	}

	void start() {
		if (running) {
			return;
		}

		running = true;
		thread = new Thread(this::listen, "facetrack-local-expression-bridge");
		thread.setDaemon(true);
		thread.start();
	}

	ReceivedExpression latest() {
		return latest.get();
	}

	int port() {
		return port;
	}

	void setExpressionListener(ExpressionListener expressionListener) {
		this.expressionListener = expressionListener;
	}

	String status() {
		return "UDP 127.0.0.1:" + port;
	}

	private void listen() {
		try (DatagramSocket datagramSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))) {
			socket = datagramSocket;
			datagramSocket.setSoTimeout(1000);
			TestMod.LOGGER.info("Listening for FaceTrack expression updates on 127.0.0.1:{}", port);

			byte[] buffer = new byte[MAX_PACKET_BYTES];
			while (running) {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				try {
					datagramSocket.receive(packet);
					ReceivedExpression received = parse(packet);
					if (received != null) {
						applyReceived(received);
					}
				} catch (SocketTimeoutException ignored) {
					// Timeout lets the thread notice close requests.
				}
			}
		} catch (SocketException exception) {
			if (running) {
				TestMod.LOGGER.warn("Unable to bind Face Expression Client bridge on 127.0.0.1:{}: {}", port, exception.getMessage());
			}
		} catch (IOException exception) {
			if (running) {
				TestMod.LOGGER.warn("Face Expression Client bridge stopped: {}", exception.getMessage());
			}
		} finally {
			socket = null;
			running = false;
		}
	}

	private ReceivedExpression parse(DatagramPacket packet) {
		String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
		String[] parts = message.split("\\|", -1);
		if (parts.length == 0) {
			return null;
		}

		long receivedAt = System.currentTimeMillis();
		if ("facetrack".equals(parts[0])) {
			return parseFaceTrack(parts, receivedAt);
		}
		if ("facetrack_parts".equals(parts[0])) {
			return parseFaceTrackParts(parts, receivedAt);
		}
		return null;
	}

	private static ReceivedExpression parseFaceTrack(String[] parts, long receivedAt) {
		if (parts.length < 12 || parseInt(parts[1], 0) != 1) {
			return null;
		}

		long capturedAt = parseLong(parts[2], receivedAt);
		ExpressionKind expression = ExpressionKind.fromWireName(parts[3]);
		ExpressionSnapshot snapshot = new ExpressionSnapshot(
				expression,
				parseFloat(parts[4]),
				capturedAt,
				parseInt(parts[5], 0),
				parseFloat(parts[6]),
				parseFloat(parts[7]),
				parseFloat(parts[8]),
				parseFloat(parts[9]),
				parseFloat(parts[10]),
				parseFloat(parts[11])
		);
		return new ReceivedExpression(snapshot, receivedAt, Source.FACETRACK_UDP);
	}

	private static ReceivedExpression parseFaceTrackParts(String[] parts, long receivedAt) {
		if (parts.length < 18 || parseInt(parts[1], 0) != 1) {
			return null;
		}

		long capturedAt = parseLong(parts[2], receivedAt);
		ExpressionKind expression = ExpressionKind.fromWireName(parts[3]);
		ExpressionParts expressionParts = ExpressionParts.fromWireNames(parts[13], parts[14], parts[15], parts[16], parts[17]);
		ExpressionSnapshot snapshot = new ExpressionSnapshot(
				expression,
				parseFloat(parts[4]),
				capturedAt,
				parseInt(parts[5], 0),
				parseFloat(parts[6]),
				parseFloat(parts[7]),
				parseFloat(parts[8]),
				parseFloat(parts[9]),
				parseFloat(parts[10]),
				parseFloat(parts[11]),
				expressionParts
		);
		return new ReceivedExpression(snapshot, receivedAt, Source.FACETRACK_UDP);
	}

	private void applyReceived(ReceivedExpression received) {
		latest.set(received);
		ExpressionListener listener = expressionListener;
		if (listener != null) {
			listener.onExpression(received);
		}
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static float parseFloat(String value) {
		try {
			return Float.parseFloat(value);
		} catch (NumberFormatException exception) {
			return 0.0F;
		}
	}

	@Override
	public void close() {
		running = false;
		DatagramSocket datagramSocket = socket;
		if (datagramSocket != null) {
			datagramSocket.close();
		}
	}

	record ReceivedExpression(ExpressionSnapshot snapshot, long receivedAtMillis, Source source) {
	}

	interface ExpressionListener {
		void onExpression(ReceivedExpression received);
	}

	enum Source {
		FACETRACK_UDP("FaceTrack UDP");

		private final String displayName;

		Source(String displayName) {
			this.displayName = displayName;
		}

		String displayName() {
			return displayName;
		}
	}
}
