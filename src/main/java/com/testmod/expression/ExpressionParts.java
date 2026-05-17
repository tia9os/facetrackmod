package com.testmod.expression;

import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record ExpressionParts(
		Mouth mouth,
		Eye leftEye,
		Eye rightEye,
		Eyebrow leftEyebrow,
		Eyebrow rightEyebrow
) {
	public static final ExpressionParts DEFAULT = new ExpressionParts(
			Mouth.NEUTRAL,
			Eye.OPEN,
			Eye.OPEN,
			Eyebrow.NEUTRAL,
			Eyebrow.NEUTRAL
	);

	public ExpressionParts {
		mouth = mouth == null ? Mouth.NEUTRAL : mouth;
		leftEye = leftEye == null ? Eye.OPEN : leftEye;
		rightEye = rightEye == null ? Eye.OPEN : rightEye;
		leftEyebrow = leftEyebrow == null ? Eyebrow.NEUTRAL : leftEyebrow;
		rightEyebrow = rightEyebrow == null ? Eyebrow.NEUTRAL : rightEyebrow;
	}

	public static ExpressionParts fromExpression(ExpressionKind expression) {
		return switch (expression == null ? ExpressionKind.NO_FACE : expression) {
			case HAPPY -> new ExpressionParts(Mouth.HAPPY, Eye.OPEN, Eye.OPEN, Eyebrow.NEUTRAL, Eyebrow.NEUTRAL);
			case SAD -> new ExpressionParts(Mouth.SAD, Eye.OPEN, Eye.OPEN, Eyebrow.SAD, Eyebrow.SAD);
			case SURPRISED -> new ExpressionParts(Mouth.SURPRISED, Eye.OPEN, Eye.OPEN, Eyebrow.RAISED, Eyebrow.RAISED);
			case TALKING -> new ExpressionParts(Mouth.TALKING, Eye.OPEN, Eye.OPEN, Eyebrow.NEUTRAL, Eyebrow.NEUTRAL);
			case BLINKING -> new ExpressionParts(Mouth.NEUTRAL, Eye.CLOSED, Eye.CLOSED, Eyebrow.NEUTRAL, Eyebrow.NEUTRAL);
			case WINKING -> new ExpressionParts(Mouth.HAPPY, Eye.CLOSED, Eye.OPEN, Eyebrow.NEUTRAL, Eyebrow.NEUTRAL);
			case FOCUSED -> new ExpressionParts(Mouth.NEUTRAL, Eye.FOCUSED, Eye.FOCUSED, Eyebrow.FOCUSED, Eyebrow.FOCUSED);
			case FUNNY -> new ExpressionParts(Mouth.FUNNY, Eye.CLOSED, Eye.OPEN, Eyebrow.RAISED, Eyebrow.RAISED);
			case NEUTRAL, NO_FACE -> DEFAULT;
		};
	}

	public static ExpressionParts fromWireNames(
			String mouth,
			String leftEye,
			String rightEye,
			String leftEyebrow,
			String rightEyebrow
	) {
		return new ExpressionParts(
				Mouth.fromWireName(mouth),
				Eye.fromWireName(leftEye),
				Eye.fromWireName(rightEye),
				Eyebrow.fromWireName(leftEyebrow),
				Eyebrow.fromWireName(rightEyebrow)
		);
	}

	public String mouthTextureName() {
		return "parts/mouth/" + mouth.wireName();
	}

	public String leftEyeTextureName() {
		return "parts/left_eye/" + leftEye.wireName();
	}

	public String rightEyeTextureName() {
		return "parts/right_eye/" + rightEye.wireName();
	}

	public String leftEyebrowTextureName() {
		return "parts/left_eyebrow/" + leftEyebrow.wireName();
	}

	public String rightEyebrowTextureName() {
		return "parts/right_eyebrow/" + rightEyebrow.wireName();
	}

	public String[] textureNames() {
		return new String[] {
				mouthTextureName(),
				leftEyeTextureName(),
				rightEyeTextureName(),
				leftEyebrowTextureName(),
				rightEyebrowTextureName()
		};
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(mouth.ordinal());
		buffer.writeVarInt(leftEye.ordinal());
		buffer.writeVarInt(rightEye.ordinal());
		buffer.writeVarInt(leftEyebrow.ordinal());
		buffer.writeVarInt(rightEyebrow.ordinal());
	}

	public static ExpressionParts read(RegistryFriendlyByteBuf buffer) {
		return new ExpressionParts(
				Mouth.byOrdinal(buffer.readVarInt()),
				Eye.byOrdinal(buffer.readVarInt()),
				Eye.byOrdinal(buffer.readVarInt()),
				Eyebrow.byOrdinal(buffer.readVarInt()),
				Eyebrow.byOrdinal(buffer.readVarInt())
		);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
	}

	public enum Mouth {
		NEUTRAL("neutral"),
		HAPPY("happy"),
		SAD("sad"),
		SURPRISED("surprised"),
		TALKING("talking"),
		FUNNY("funny");

		private final String wireName;

		Mouth(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static Mouth byOrdinal(int ordinal) {
			Mouth[] values = values();
			return ordinal < 0 || ordinal >= values.length ? NEUTRAL : values[ordinal];
		}

		public static Mouth fromWireName(String value) {
			String normalized = normalize(value);
			for (Mouth mouth : values()) {
				if (mouth.wireName.equals(normalized)) {
					return mouth;
				}
			}
			return NEUTRAL;
		}
	}

	public enum Eye {
		OPEN("open"),
		CLOSED("closed"),
		FOCUSED("focused");

		private final String wireName;

		Eye(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static Eye byOrdinal(int ordinal) {
			Eye[] values = values();
			return ordinal < 0 || ordinal >= values.length ? OPEN : values[ordinal];
		}

		public static Eye fromWireName(String value) {
			String normalized = normalize(value);
			for (Eye eye : values()) {
				if (eye.wireName.equals(normalized)) {
					return eye;
				}
			}
			return OPEN;
		}
	}

	public enum Eyebrow {
		NEUTRAL("neutral"),
		RAISED("raised"),
		SAD("sad"),
		FOCUSED("focused");

		private final String wireName;

		Eyebrow(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static Eyebrow byOrdinal(int ordinal) {
			Eyebrow[] values = values();
			return ordinal < 0 || ordinal >= values.length ? NEUTRAL : values[ordinal];
		}

		public static Eyebrow fromWireName(String value) {
			String normalized = normalize(value);
			for (Eyebrow eyebrow : values()) {
				if (eyebrow.wireName.equals(normalized)) {
					return eyebrow;
				}
			}
			return NEUTRAL;
		}
	}
}
