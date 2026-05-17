package com.testmod.expression;

import com.testmod.TestMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientExpressionPayload(ExpressionSnapshot snapshot) implements CustomPacketPayload {
	public static final Type<ClientExpressionPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "client_expression")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, ClientExpressionPayload> CODEC =
			CustomPacketPayload.codec(ClientExpressionPayload::write, ClientExpressionPayload::read);

	public ClientExpressionPayload {
		snapshot = snapshot == null ? ExpressionSnapshot.noFace(System.currentTimeMillis()) : snapshot;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		snapshot.write(buffer);
	}

	private static ClientExpressionPayload read(RegistryFriendlyByteBuf buffer) {
		return new ClientExpressionPayload(ExpressionSnapshot.read(buffer));
	}
}
