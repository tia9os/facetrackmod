package com.testmod.expression;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class ExpressionPayloads {
	private static boolean registered;

	private ExpressionPayloads() {
	}

	public static void register() {
		if (registered) {
			return;
		}

		PayloadTypeRegistry.playC2S().register(ClientExpressionPayload.TYPE, ClientExpressionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ClientExpressionTexturesPayload.TYPE, ClientExpressionTexturesPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PlayerExpressionPayload.TYPE, PlayerExpressionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PlayerExpressionTexturesPayload.TYPE, PlayerExpressionTexturesPayload.CODEC);
		registered = true;
	}
}
