package com.testmod.expression;

import net.minecraft.network.RegistryFriendlyByteBuf;

public record ExpressionSnapshot(
		ExpressionKind expression,
		float confidence,
		long capturedAtMillis,
		int faceCount,
		float smileScore,
		float mouthOpenScore,
		float mouthWideScore,
		float sadScore,
		float blinkScore,
		float winkScore,
		ExpressionParts parts
) {
	public ExpressionSnapshot(
			ExpressionKind expression,
			float confidence,
			long capturedAtMillis,
			int faceCount,
			float smileScore,
			float mouthOpenScore,
			float mouthWideScore,
			float sadScore,
			float blinkScore,
			float winkScore
	) {
		this(
				expression,
				confidence,
				capturedAtMillis,
				faceCount,
				smileScore,
				mouthOpenScore,
				mouthWideScore,
				sadScore,
				blinkScore,
				winkScore,
				ExpressionParts.fromExpression(expression)
		);
	}

	public ExpressionSnapshot {
		expression = expression == null ? ExpressionKind.NO_FACE : expression;
		confidence = clamp(confidence);
		capturedAtMillis = Math.max(0L, capturedAtMillis);
		faceCount = Math.max(0, faceCount);
		smileScore = clamp(smileScore);
		mouthOpenScore = clamp(mouthOpenScore);
		mouthWideScore = clamp(mouthWideScore);
		sadScore = clamp(sadScore);
		blinkScore = clamp(blinkScore);
		winkScore = clamp(winkScore);
		parts = parts == null ? ExpressionParts.fromExpression(expression) : parts;
	}

	public static ExpressionSnapshot noFace(long capturedAtMillis) {
		return new ExpressionSnapshot(ExpressionKind.NO_FACE, 0.0F, capturedAtMillis, 0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
	}

	public boolean renderable() {
		return expression.renderable() && confidence > 0.05F;
	}

	public boolean technicalDifficulties() {
		return expression == ExpressionKind.NO_FACE;
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(expression.ordinal());
		buffer.writeFloat(confidence);
		buffer.writeLong(capturedAtMillis);
		buffer.writeVarInt(faceCount);
		buffer.writeFloat(smileScore);
		buffer.writeFloat(mouthOpenScore);
		buffer.writeFloat(mouthWideScore);
		buffer.writeFloat(sadScore);
		buffer.writeFloat(blinkScore);
		buffer.writeFloat(winkScore);
		parts.write(buffer);
	}

	public static ExpressionSnapshot read(RegistryFriendlyByteBuf buffer) {
		return new ExpressionSnapshot(
				ExpressionKind.byOrdinal(buffer.readVarInt()),
				buffer.readFloat(),
				buffer.readLong(),
				buffer.readVarInt(),
				buffer.readFloat(),
				buffer.readFloat(),
				buffer.readFloat(),
				buffer.readFloat(),
				buffer.readFloat(),
				buffer.readFloat(),
				ExpressionParts.read(buffer)
		);
	}

	private static float clamp(float value) {
		if (Float.isNaN(value) || Float.isInfinite(value)) {
			return 0.0F;
		}
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
