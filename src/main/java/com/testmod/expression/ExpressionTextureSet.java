package com.testmod.expression;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record ExpressionTextureSet(Map<String, byte[]> textures) {
	public static final String BASE_TEXTURE_NAME = "base";
	public static final int TEXTURE_SIZE = 8;
	public static final int MAX_TEXTURE_BYTES = 16 * 1024;
	public static final List<String> LEGACY_TEXTURE_NAMES = List.of(
			BASE_TEXTURE_NAME,
			ExpressionKind.NEUTRAL.wireName(),
			ExpressionKind.HAPPY.wireName(),
			ExpressionKind.SAD.wireName(),
			ExpressionKind.SURPRISED.wireName(),
			ExpressionKind.TALKING.wireName(),
			ExpressionKind.BLINKING.wireName(),
			ExpressionKind.WINKING.wireName(),
			ExpressionKind.FOCUSED.wireName(),
			ExpressionKind.FUNNY.wireName()
	);
	public static final List<String> PART_TEXTURE_NAMES = createPartTextureNames();
	public static final List<String> TEXTURE_NAMES = createTextureNames();
	public static final ExpressionTextureSet EMPTY = new ExpressionTextureSet(Map.of());

	public ExpressionTextureSet {
		Map<String, byte[]> sanitized = new LinkedHashMap<>();
		if (textures != null) {
			for (String textureName : TEXTURE_NAMES) {
				byte[] bytes = textures.get(textureName);
				if (bytes != null && bytes.length > 0 && bytes.length <= MAX_TEXTURE_BYTES) {
					sanitized.put(textureName, bytes.clone());
				}
			}
		}
		textures = Collections.unmodifiableMap(sanitized);
	}

	public boolean isEmpty() {
		return textures.isEmpty();
	}

	public byte[] texture(String textureName) {
		byte[] bytes = textures.get(textureName);
		return bytes == null ? null : bytes.clone();
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(textures.size());
		for (Map.Entry<String, byte[]> entry : textures.entrySet()) {
			buffer.writeUtf(entry.getKey(), 32);
			buffer.writeByteArray(entry.getValue());
		}
	}

	public static ExpressionTextureSet read(RegistryFriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 0 || count > TEXTURE_NAMES.size()) {
			throw new DecoderException("Invalid face expression texture count: " + count);
		}

		Map<String, byte[]> textures = new LinkedHashMap<>();
		for (int index = 0; index < count; index++) {
			String textureName = buffer.readUtf(32);
			byte[] bytes = buffer.readByteArray(MAX_TEXTURE_BYTES);
			if (TEXTURE_NAMES.contains(textureName)) {
				textures.put(textureName, bytes);
			}
		}
		return new ExpressionTextureSet(textures);
	}

	private static List<String> createTextureNames() {
		List<String> names = new ArrayList<>(LEGACY_TEXTURE_NAMES.size() + PART_TEXTURE_NAMES.size());
		names.addAll(LEGACY_TEXTURE_NAMES);
		names.addAll(PART_TEXTURE_NAMES);
		return List.copyOf(names);
	}

	private static List<String> createPartTextureNames() {
		List<String> names = new ArrayList<>();
		for (ExpressionParts.Mouth mouth : ExpressionParts.Mouth.values()) {
			names.add("parts/mouth/" + mouth.wireName());
		}
		for (ExpressionParts.Eye eye : ExpressionParts.Eye.values()) {
			names.add("parts/left_eye/" + eye.wireName());
			names.add("parts/right_eye/" + eye.wireName());
		}
		for (ExpressionParts.Eyebrow eyebrow : ExpressionParts.Eyebrow.values()) {
			names.add("parts/left_eyebrow/" + eyebrow.wireName());
			names.add("parts/right_eyebrow/" + eyebrow.wireName());
		}
		return List.copyOf(names);
	}
}
