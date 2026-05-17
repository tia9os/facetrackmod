package com.testmod.client;

import com.testmod.TestMod;
import com.testmod.expression.ClientExpressionPayload;
import com.testmod.expression.ClientExpressionTexturesPayload;
import com.testmod.expression.ExpressionKind;
import com.testmod.expression.ExpressionParts;
import com.testmod.expression.ExpressionSnapshot;
import com.testmod.expression.ExpressionTextureSet;
import com.testmod.expression.PlayerExpressionPayload;
import com.testmod.expression.PlayerExpressionTexturesPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

public final class ClientExpressionSync {
	private static final long LOCAL_STALE_MILLIS = 2500L;
	private static final long NO_FACE_GRACE_MILLIS = 350L;
	private static final long SEND_INTERVAL_MILLIS = 0L;
	private static final long HEARTBEAT_MILLIS = 250L;
	private static final long TEXTURE_UPLOAD_RETRY_MILLIS = 1000L;
	private static final long SERVER_SYNC_CHECK_RETRY_MILLIS = 1000L;
	private static final LocalExpressionReceiver RECEIVER = new LocalExpressionReceiver(
			Integer.getInteger("facetrack.bridge.port", LocalExpressionReceiver.DEFAULT_PORT)
	);

	private static ExpressionKind lastSentExpression = ExpressionKind.NO_FACE;
	private static ExpressionParts lastSentParts = ExpressionParts.DEFAULT;
	private static float lastSentConfidence;
	private static long lastSentAtMillis;
	private static ExpressionSnapshot lastRenderableLocalSnapshot;
	private static long lastRenderableLocalAtMillis;
	private static ExpressionSnapshot triggeredSnapshot;
	private static int triggeredTicksRemaining;
	private static boolean pendingTextureUpload;
	private static long lastTextureUploadAttemptMillis;
	private static boolean serverExpressionSyncAvailable;
	private static boolean serverTextureSyncAvailable;
	private static boolean serverSyncChecked;
	private static long lastServerSyncCheckMillis;

	private ClientExpressionSync() {
	}

	public static void initialize() {
		FaceHudConfig.load();
		RECEIVER.setExpressionListener(ClientExpressionSync::onExpressionReceived);
		RECEIVER.start();
		FaceTrackClientCommands.register(RECEIVER);
		ExpressionFaceHud.register();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> ExpressionFaceTextures.register(client.getTextureManager()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RECEIVER.close());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			pendingTextureUpload = true;
			lastTextureUploadAttemptMillis = 0L;
			serverExpressionSyncAvailable = false;
			serverTextureSyncAvailable = false;
			serverSyncChecked = false;
			lastServerSyncCheckMillis = 0L;
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientExpressionStore.clear();
			ExpressionFaceTextures.clearPlayerTextures();
			lastSentExpression = ExpressionKind.NO_FACE;
			lastSentParts = ExpressionParts.DEFAULT;
			lastSentConfidence = 0.0F;
			lastSentAtMillis = 0L;
			lastRenderableLocalSnapshot = null;
			lastRenderableLocalAtMillis = 0L;
			clearTriggeredExpression();
			pendingTextureUpload = false;
			lastTextureUploadAttemptMillis = 0L;
			serverExpressionSyncAvailable = false;
			serverTextureSyncAvailable = false;
			serverSyncChecked = false;
			lastServerSyncCheckMillis = 0L;
		});

		ClientPlayNetworking.registerGlobalReceiver(PlayerExpressionPayload.TYPE, (payload, context) -> {
			ClientExpressionStore.apply(payload.playerId(), payload.snapshot());
		});

		ClientPlayNetworking.registerGlobalReceiver(PlayerExpressionTexturesPayload.TYPE, (payload, context) -> {
			Minecraft.getInstance().execute(() -> ExpressionFaceTextures.applyPlayerTextures(payload.playerId(), payload.textures()));
		});

		ClientTickEvents.END_CLIENT_TICK.register(ClientExpressionSync::tick);

		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityRenderer instanceof PlayerRenderer playerRenderer) {
				registrationHelper.register(new ExpressionFaceLayer(playerRenderer));
			}
		});

		TestMod.LOGGER.info("Local expression bridge initialized on UDP port {}", RECEIVER.port());
	}

	private static void tick(Minecraft client) {
		if (client.player == null) {
			return;
		}

		ExpressionSnapshot snapshot = localSnapshotForRender();
		ClientExpressionStore.apply(client.player.getUUID(), snapshot);
		updateServerSyncAvailability(client);
		uploadTexturesIfNeeded(client);
		sendSnapshotIfNeeded(client, snapshot);
		advanceTriggeredExpression();
	}

	private static void onExpressionReceived(LocalExpressionReceiver.ReceivedExpression received) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player == null) {
				return;
			}

			ExpressionSnapshot snapshot = localSnapshotForRender();
			ClientExpressionStore.apply(client.player.getUUID(), snapshot);
			updateServerSyncAvailability(client);
			sendSnapshotIfNeeded(client, snapshot);
		});
	}

	private static void sendSnapshotIfNeeded(Minecraft client, ExpressionSnapshot snapshot) {
		if (client.getConnection() == null || !serverExpressionSyncAvailable) {
			return;
		}

		long now = System.currentTimeMillis();
		if (!shouldSend(snapshot, now)) {
			return;
		}

		ClientPlayNetworking.send(new ClientExpressionPayload(snapshot));
		lastSentExpression = snapshot.expression();
		lastSentParts = snapshot.parts();
		lastSentConfidence = snapshot.confidence();
		lastSentAtMillis = now;
	}

	static ExpressionSnapshot localSnapshotForRender() {
		long now = System.currentTimeMillis();
		ExpressionSnapshot triggered = triggeredSnapshot(now);
		if (triggered != null) {
			if (triggered.renderable()) {
				lastRenderableLocalSnapshot = triggered;
				lastRenderableLocalAtMillis = now;
			}
			return triggered;
		}

		LocalExpressionReceiver.ReceivedExpression latest = RECEIVER.latest();
		if (latest != null && now - latest.receivedAtMillis() <= LOCAL_STALE_MILLIS) {
			ExpressionSnapshot snapshot = latest.snapshot();
			if (snapshot.renderable()) {
				lastRenderableLocalSnapshot = snapshot;
				lastRenderableLocalAtMillis = now;
				return snapshot;
			}
		}

		if (lastRenderableLocalSnapshot != null && now - lastRenderableLocalAtMillis <= NO_FACE_GRACE_MILLIS) {
			return lastRenderableLocalSnapshot;
		}
		return ExpressionSnapshot.noFace(now);
	}

	static synchronized void triggerExpression(ExpressionKind expression, int ticks) {
		ExpressionKind safeExpression = expression == null ? ExpressionKind.NO_FACE : expression;
		ExpressionParts parts = ExpressionParts.fromExpression(safeExpression);
		triggeredSnapshot = createTriggeredSnapshot(safeExpression, parts, System.currentTimeMillis());
		triggeredTicksRemaining = Math.max(1, ticks);
	}

	static synchronized void triggerParts(ExpressionParts parts, int ticks) {
		ExpressionParts safeParts = parts == null ? ExpressionParts.DEFAULT : parts;
		triggeredSnapshot = createTriggeredSnapshot(ExpressionKind.NEUTRAL, safeParts, System.currentTimeMillis());
		triggeredTicksRemaining = Math.max(1, ticks);
	}

	static synchronized void clearTriggeredExpression() {
		triggeredSnapshot = null;
		triggeredTicksRemaining = 0;
	}

	static synchronized String triggeredExpressionStatus() {
		if (triggeredSnapshot == null || triggeredTicksRemaining <= 0) {
			return "Triggered face: none";
		}

		ExpressionParts parts = triggeredSnapshot.parts();
		return "Triggered face: " + triggeredSnapshot.expression().wireName()
				+ ", " + triggeredTicksRemaining + " tick(s) left"
				+ ", parts " + parts.mouth().wireName()
				+ " " + parts.leftEye().wireName()
				+ " " + parts.rightEye().wireName()
				+ " " + parts.leftEyebrow().wireName()
				+ " " + parts.rightEyebrow().wireName();
	}

	private static synchronized ExpressionSnapshot triggeredSnapshot(long capturedAtMillis) {
		if (triggeredSnapshot == null || triggeredTicksRemaining <= 0) {
			return null;
		}
		return copyWithCapturedAt(triggeredSnapshot, capturedAtMillis);
	}

	private static synchronized void advanceTriggeredExpression() {
		if (triggeredTicksRemaining <= 0) {
			triggeredSnapshot = null;
			triggeredTicksRemaining = 0;
			return;
		}

		triggeredTicksRemaining--;
		if (triggeredTicksRemaining <= 0) {
			triggeredSnapshot = null;
		}
	}

	private static ExpressionSnapshot createTriggeredSnapshot(ExpressionKind expression, ExpressionParts parts, long capturedAtMillis) {
		if (expression == ExpressionKind.NO_FACE) {
			return new ExpressionSnapshot(ExpressionKind.NO_FACE, 0.0F, capturedAtMillis, 0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, parts);
		}

		return new ExpressionSnapshot(
				expression,
				1.0F,
				capturedAtMillis,
				1,
				score(expression == ExpressionKind.HAPPY || expression == ExpressionKind.FUNNY),
				score(expression == ExpressionKind.SURPRISED || expression == ExpressionKind.TALKING),
				score(expression == ExpressionKind.HAPPY || expression == ExpressionKind.FUNNY),
				score(expression == ExpressionKind.SAD),
				score(expression == ExpressionKind.BLINKING),
				score(expression == ExpressionKind.WINKING),
				parts
		);
	}

	private static ExpressionSnapshot copyWithCapturedAt(ExpressionSnapshot snapshot, long capturedAtMillis) {
		return new ExpressionSnapshot(
				snapshot.expression(),
				snapshot.confidence(),
				capturedAtMillis,
				snapshot.faceCount(),
				snapshot.smileScore(),
				snapshot.mouthOpenScore(),
				snapshot.mouthWideScore(),
				snapshot.sadScore(),
				snapshot.blinkScore(),
				snapshot.winkScore(),
				snapshot.parts()
		);
	}

	private static float score(boolean active) {
		return active ? 1.0F : 0.0F;
	}

	private static void uploadTexturesIfNeeded(Minecraft client) {
		if (!pendingTextureUpload || client.getConnection() == null) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastTextureUploadAttemptMillis < TEXTURE_UPLOAD_RETRY_MILLIS) {
			return;
		}
		lastTextureUploadAttemptMillis = now;

		if (serverSyncChecked && !serverTextureSyncAvailable) {
			pendingTextureUpload = false;
			TestMod.LOGGER.info("FaceTrack server texture sync is unavailable; local face textures will render only on this client.");
			return;
		}
		if (!serverTextureSyncAvailable) {
			return;
		}

		ExpressionTextureSet textureSet = ExpressionFaceTextures.readLocalTextureSet();
		if (textureSet.isEmpty()) {
			TestMod.LOGGER.warn("No face expression textures were uploaded because the local texture set is empty.");
			return;
		}

		ClientPlayNetworking.send(new ClientExpressionTexturesPayload(textureSet));
		pendingTextureUpload = false;
		TestMod.LOGGER.info("Uploaded {} face expression textures to the server.", textureSet.textures().size());
	}

	private static void updateServerSyncAvailability(Minecraft client) {
		if (client.getConnection() == null) {
			serverExpressionSyncAvailable = false;
			serverTextureSyncAvailable = false;
			serverSyncChecked = false;
			lastServerSyncCheckMillis = 0L;
			return;
		}

		long now = System.currentTimeMillis();
		if (serverSyncChecked && serverExpressionSyncAvailable && serverTextureSyncAvailable) {
			return;
		}
		if (serverSyncChecked && now - lastServerSyncCheckMillis < SERVER_SYNC_CHECK_RETRY_MILLIS) {
			return;
		}
		lastServerSyncCheckMillis = now;

		boolean previousTextureAvailable = serverTextureSyncAvailable;
		boolean expressionAvailable = canSend();
		boolean textureAvailable = canSendTextures();
		boolean changed = !serverSyncChecked
				|| serverExpressionSyncAvailable != expressionAvailable
				|| serverTextureSyncAvailable != textureAvailable;
		serverExpressionSyncAvailable = expressionAvailable;
		serverTextureSyncAvailable = textureAvailable;
		serverSyncChecked = true;
		if (textureAvailable && !previousTextureAvailable) {
			pendingTextureUpload = true;
			lastTextureUploadAttemptMillis = 0L;
		}

		if (!changed) {
			return;
		}
		if (serverExpressionSyncAvailable) {
			TestMod.LOGGER.info("FaceTrack server sync is available.");
		} else {
			TestMod.LOGGER.info("FaceTrack server sync is unavailable; expressions will render locally only.");
		}
	}

	static String serverSyncStatus() {
		if (!serverSyncChecked) {
			return "Server sync: checking";
		}
		if (serverExpressionSyncAvailable && serverTextureSyncAvailable) {
			return "Server sync: available";
		}
		if (serverExpressionSyncAvailable) {
			return "Server sync: expressions only";
		}
		return "Server sync: local only";
	}

	private static boolean canSend() {
		try {
			return ClientPlayNetworking.canSend(ClientExpressionPayload.TYPE);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean canSendTextures() {
		try {
			return ClientPlayNetworking.canSend(ClientExpressionTexturesPayload.TYPE);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean shouldSend(ExpressionSnapshot snapshot, long now) {
		if (snapshot.expression() != lastSentExpression) {
			return true;
		}
		if (!snapshot.parts().equals(lastSentParts)) {
			return true;
		}
		if (now - lastSentAtMillis < SEND_INTERVAL_MILLIS) {
			return false;
		}
		if (Math.abs(snapshot.confidence() - lastSentConfidence) >= 0.08F) {
			return true;
		}
		return now - lastSentAtMillis >= HEARTBEAT_MILLIS;
	}
}
