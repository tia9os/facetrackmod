package com.testmod.expression;

import com.testmod.TestMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientExpressionTexturesPayload(ExpressionTextureSet textures) implements CustomPacketPayload {
	public static final Type<ClientExpressionTexturesPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "client_expression_textures")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, ClientExpressionTexturesPayload> CODEC =
			CustomPacketPayload.codec(ClientExpressionTexturesPayload::write, ClientExpressionTexturesPayload::read);

	public ClientExpressionTexturesPayload {
		textures = textures == null ? ExpressionTextureSet.EMPTY : textures;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		textures.write(buffer);
	}

	private static ClientExpressionTexturesPayload read(RegistryFriendlyByteBuf buffer) {
		return new ClientExpressionTexturesPayload(ExpressionTextureSet.read(buffer));
	}
}
