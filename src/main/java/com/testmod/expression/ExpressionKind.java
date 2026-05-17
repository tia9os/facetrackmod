package com.testmod.expression;

import java.util.Locale;

public enum ExpressionKind {
	NO_FACE("no_face"),
	NEUTRAL("neutral"),
	HAPPY("happy"),
	SAD("sad"),
	SURPRISED("surprised"),
	TALKING("talking"),
	BLINKING("blinking"),
	WINKING("winking"),
	FOCUSED("focused"),
	FUNNY("funny");

	private final String wireName;

	ExpressionKind(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public boolean renderable() {
		return this != NO_FACE;
	}

	public static ExpressionKind byOrdinal(int ordinal) {
		ExpressionKind[] values = values();
		if (ordinal < 0 || ordinal >= values.length) {
			return NO_FACE;
		}
		return values[ordinal];
	}

	public static ExpressionKind fromWireName(String value) {
		if (value == null || value.isBlank()) {
			return NO_FACE;
		}

		String normalized = value.trim()
				.toLowerCase(Locale.ROOT)
				.replace(' ', '_')
				.replace('-', '_');
		for (ExpressionKind kind : values()) {
			if (kind.wireName.equals(normalized)) {
				return kind;
			}
		}
		return NO_FACE;
	}
}
