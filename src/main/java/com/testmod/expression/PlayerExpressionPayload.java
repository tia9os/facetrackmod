package com.testmod.expression;

import com.testmod.TestMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerExpressionPayload(UUID playerId, ExpressionSnapshot snapshot) implements CustomPacketPayload {
	public static final Type<PlayerExpressionPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "player_expression")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, PlayerExpressionPayload> CODEC =
			CustomPacketPayload.codec(PlayerExpressionPayload::write, PlayerExpressionPayload::read);

	public PlayerExpressionPayload {
		playerId = playerId == null ? new UUID(0L, 0L) : playerId;
		snapshot = snapshot == null ? ExpressionSnapshot.noFace(System.currentTimeMillis()) : snapshot;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeUUID(playerId);
		snapshot.write(buffer);
	}

	private static PlayerExpressionPayload read(RegistryFriendlyByteBuf buffer) {
		return new PlayerExpressionPayload(buffer.readUUID(), ExpressionSnapshot.read(buffer));
	}
}
