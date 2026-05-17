package com.testmod.expression;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ServerExpressionSync {
	private static final Map<UUID, ExpressionSnapshot> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, ExpressionTextureSet> TEXTURE_SETS = new ConcurrentHashMap<>();

	private ServerExpressionSync() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ClientExpressionPayload.TYPE, (payload, context) -> {
			UUID playerId = context.player().getUUID();
			STATES.put(playerId, payload.snapshot());
			broadcast(context.server(), new PlayerExpressionPayload(playerId, payload.snapshot()));
		});

		ServerPlayNetworking.registerGlobalReceiver(ClientExpressionTexturesPayload.TYPE, (payload, context) -> {
			UUID playerId = context.player().getUUID();
			if (payload.textures().isEmpty()) {
				TEXTURE_SETS.remove(playerId);
			} else {
				TEXTURE_SETS.put(playerId, payload.textures());
			}
			broadcast(context.server(), new PlayerExpressionTexturesPayload(playerId, payload.textures()));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer joiningPlayer = handler.player;
			for (Map.Entry<UUID, ExpressionSnapshot> entry : STATES.entrySet()) {
				if (ServerPlayNetworking.canSend(joiningPlayer, PlayerExpressionPayload.TYPE)) {
					ServerPlayNetworking.send(joiningPlayer, new PlayerExpressionPayload(entry.getKey(), entry.getValue()));
				}
			}
			for (Map.Entry<UUID, ExpressionTextureSet> entry : TEXTURE_SETS.entrySet()) {
				if (ServerPlayNetworking.canSend(joiningPlayer, PlayerExpressionTexturesPayload.TYPE)) {
					ServerPlayNetworking.send(joiningPlayer, new PlayerExpressionTexturesPayload(entry.getKey(), entry.getValue()));
				}
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.player.getUUID();
			STATES.remove(playerId);
			TEXTURE_SETS.remove(playerId);
			broadcastExcept(server, playerId, new PlayerExpressionPayload(playerId, ExpressionSnapshot.noFace(System.currentTimeMillis())));
			broadcastExcept(server, playerId, new PlayerExpressionTexturesPayload(playerId, ExpressionTextureSet.EMPTY));
		});
	}

	private static void broadcast(MinecraftServer server, PlayerExpressionPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, PlayerExpressionPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static void broadcast(MinecraftServer server, PlayerExpressionTexturesPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, PlayerExpressionTexturesPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static void broadcastExcept(MinecraftServer server, UUID excludedPlayerId, PlayerExpressionPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.getUUID().equals(excludedPlayerId) && ServerPlayNetworking.canSend(player, PlayerExpressionPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static void broadcastExcept(MinecraftServer server, UUID excludedPlayerId, PlayerExpressionTexturesPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.getUUID().equals(excludedPlayerId) && ServerPlayNetworking.canSend(player, PlayerExpressionTexturesPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}
}
