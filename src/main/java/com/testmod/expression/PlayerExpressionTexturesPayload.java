package com.testmod.expression;

import com.testmod.TestMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerExpressionTexturesPayload(UUID playerId, ExpressionTextureSet textures) implements CustomPacketPayload {
	public static final Type<PlayerExpressionTexturesPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "player_expression_textures")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, PlayerExpressionTexturesPayload> CODEC =
			CustomPacketPayload.codec(PlayerExpressionTexturesPayload::write, PlayerExpressionTexturesPayload::read);

	public PlayerExpressionTexturesPayload {
		playerId = playerId == null ? new UUID(0L, 0L) : playerId;
		textures = textures == null ? ExpressionTextureSet.EMPTY : textures;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeUUID(playerId);
		textures.write(buffer);
	}

	private static PlayerExpressionTexturesPayload read(RegistryFriendlyByteBuf buffer) {
		return new PlayerExpressionTexturesPayload(buffer.readUUID(), ExpressionTextureSet.read(buffer));
	}
}
