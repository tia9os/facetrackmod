package com.testmod.client;

import com.testmod.TestMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

final class FaceHudConfig {
	private static final String CONFIG_FILE_NAME = "facetrackermod-client.properties";
	private static final HudSettings DEFAULT_SETTINGS = new HudSettings(false, Corner.TOP_RIGHT, 8, 8, 6.0F);
	private static final boolean DEFAULT_TECHNICAL_DIFFICULTIES_FACE_ENABLED = true;
	private static HudSettings settings = DEFAULT_SETTINGS;
	private static boolean technicalDifficultiesFaceEnabled = DEFAULT_TECHNICAL_DIFFICULTIES_FACE_ENABLED;
	private static boolean loaded;

	private FaceHudConfig() {
	}

	static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;

		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			save();
			return;
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			properties.load(reader);
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not load FaceTrack client config '{}'. Defaults will be used.", file, exception);
			return;
		}

		settings = new HudSettings(
				booleanValue(properties, "hud.enabled", DEFAULT_SETTINGS.enabled()),
				Corner.fromConfigValue(properties.getProperty("hud.corner")).orElse(DEFAULT_SETTINGS.corner()),
				intValue(properties, "hud.offsetX", DEFAULT_SETTINGS.offsetX()),
				intValue(properties, "hud.offsetY", DEFAULT_SETTINGS.offsetY()),
				floatValue(properties, "hud.scale", DEFAULT_SETTINGS.scale())
		).sanitized();
		technicalDifficultiesFaceEnabled = booleanValue(
				properties,
				"technicalDifficultiesFace.enabled",
				DEFAULT_TECHNICAL_DIFFICULTIES_FACE_ENABLED
		);
		save();
	}

	static synchronized HudSettings snapshot() {
		load();
		return settings;
	}

	static synchronized void setEnabled(boolean enabled) {
		load();
		settings = new HudSettings(enabled, settings.corner(), settings.offsetX(), settings.offsetY(), settings.scale()).sanitized();
		save();
	}

	static synchronized void setCorner(Corner corner) {
		load();
		settings = new HudSettings(settings.enabled(), corner, settings.offsetX(), settings.offsetY(), settings.scale()).sanitized();
		save();
	}

	static synchronized void setSide(Side side) {
		load();
		if (side == null) {
			return;
		}

		int offsetX = settings.offsetX();
		int offsetY = settings.offsetY();
		if (side == Side.LEFT || side == Side.RIGHT) {
			offsetY = 0;
		} else {
			offsetX = 0;
		}
		settings = new HudSettings(settings.enabled(), side.corner(), offsetX, offsetY, settings.scale()).sanitized();
		save();
	}

	static synchronized void setOffset(int offsetX, int offsetY) {
		load();
		settings = new HudSettings(settings.enabled(), settings.corner(), offsetX, offsetY, settings.scale()).sanitized();
		save();
	}

	static synchronized void setScale(float scale) {
		load();
		settings = new HudSettings(settings.enabled(), settings.corner(), settings.offsetX(), settings.offsetY(), scale).sanitized();
		save();
	}

	static synchronized boolean technicalDifficultiesFaceEnabled() {
		load();
		return technicalDifficultiesFaceEnabled;
	}

	static synchronized void setTechnicalDifficultiesFaceEnabled(boolean enabled) {
		load();
		technicalDifficultiesFaceEnabled = enabled;
		save();
	}

	static synchronized String technicalDifficultiesFaceStatus() {
		load();
		return "Technical difficulties face: " + (technicalDifficultiesFaceEnabled ? "on" : "off");
	}

	static synchronized String status() {
		load();
		return "Face HUD: " + (settings.enabled() ? "on" : "off")
				+ ", position " + settings.corner().configValue()
				+ ", offset " + settings.offsetX() + " " + settings.offsetY()
				+ ", scale " + String.format(Locale.ROOT, "%.2f", settings.scale());
	}

	private static void save() {
		Path file = configFile();
		try {
			Files.createDirectories(file.getParent());
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not create FaceTrack client config folder '{}'.", file.getParent(), exception);
			return;
		}

		Properties properties = new Properties();
		properties.setProperty("hud.enabled", Boolean.toString(settings.enabled()));
		properties.setProperty("hud.corner", settings.corner().configValue());
		properties.setProperty("hud.offsetX", Integer.toString(settings.offsetX()));
		properties.setProperty("hud.offsetY", Integer.toString(settings.offsetY()));
		properties.setProperty("hud.scale", String.format(Locale.ROOT, "%.2f", settings.scale()));
		properties.setProperty("technicalDifficultiesFace.enabled", Boolean.toString(technicalDifficultiesFaceEnabled));

		try (Writer writer = Files.newBufferedWriter(file)) {
			properties.store(writer, "FaceTrackerMod client settings");
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not save FaceTrack client config '{}'.", file, exception);
		}
	}

	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
	}

	private static boolean booleanValue(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return Boolean.parseBoolean(value.trim());
	}

	private static int intValue(Properties properties, String key, int fallback) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static float floatValue(Properties properties, String key, float fallback) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Float.parseFloat(value.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	record HudSettings(boolean enabled, Corner corner, int offsetX, int offsetY, float scale) {
		private HudSettings sanitized() {
			return new HudSettings(
					enabled,
					corner == null ? DEFAULT_SETTINGS.corner() : corner,
					clamp(offsetX, -10000, 10000),
					clamp(offsetY, -10000, 10000),
					Math.max(1.0F, Math.min(32.0F, scale))
			);
		}

		int pixelSize(int sourceTextureSize) {
			return Math.max(1, Math.round(sourceTextureSize * scale));
		}
	}

	enum Corner {
		TOP_LEFT("top_left", Horizontal.LEFT, Vertical.TOP),
		TOP("top", Horizontal.CENTER, Vertical.TOP),
		TOP_RIGHT("top_right", Horizontal.RIGHT, Vertical.TOP),
		RIGHT("right", Horizontal.RIGHT, Vertical.CENTER),
		BOTTOM_RIGHT("bottom_right", Horizontal.RIGHT, Vertical.BOTTOM),
		BOTTOM("bottom", Horizontal.CENTER, Vertical.BOTTOM),
		BOTTOM_LEFT("bottom_left", Horizontal.LEFT, Vertical.BOTTOM),
		LEFT("left", Horizontal.LEFT, Vertical.CENTER);

		private final String configValue;
		private final Horizontal horizontal;
		private final Vertical vertical;

		Corner(String configValue, Horizontal horizontal, Vertical vertical) {
			this.configValue = configValue;
			this.horizontal = horizontal;
			this.vertical = vertical;
		}

		String configValue() {
			return configValue;
		}

		int x(int screenWidth, int size, int offsetX) {
			return horizontal.position(screenWidth, size, offsetX);
		}

		int y(int screenHeight, int size, int offsetY) {
			return vertical.position(screenHeight, size, offsetY);
		}

		static java.util.Optional<Corner> fromConfigValue(String value) {
			if (value == null || value.isBlank()) {
				return java.util.Optional.empty();
			}

			String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
			for (Corner corner : values()) {
				if (corner.configValue.equals(normalized)) {
					return java.util.Optional.of(corner);
				}
			}
			return java.util.Optional.empty();
		}
	}

	enum Side {
		LEFT("left", Corner.LEFT),
		RIGHT("right", Corner.RIGHT),
		TOP("top", Corner.TOP),
		BOTTOM("bottom", Corner.BOTTOM);

		private final String configValue;
		private final Corner corner;

		Side(String configValue, Corner corner) {
			this.configValue = configValue;
			this.corner = corner;
		}

		Corner corner() {
			return corner;
		}

		static java.util.Optional<Side> fromConfigValue(String value) {
			if (value == null || value.isBlank()) {
				return java.util.Optional.empty();
			}

			String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
			for (Side side : values()) {
				if (side.configValue.equals(normalized)) {
					return java.util.Optional.of(side);
				}
			}
			return java.util.Optional.empty();
		}
	}

	private enum Horizontal {
		LEFT {
			@Override
			int position(int screenWidth, int size, int offset) {
				return offset;
			}
		},
		CENTER {
			@Override
			int position(int screenWidth, int size, int offset) {
				return (screenWidth - size) / 2 + offset;
			}
		},
		RIGHT {
			@Override
			int position(int screenWidth, int size, int offset) {
				return screenWidth - size - offset;
			}
		};

		abstract int position(int screenWidth, int size, int offset);
	}

	private enum Vertical {
		TOP {
			@Override
			int position(int screenHeight, int size, int offset) {
				return offset;
			}
		},
		CENTER {
			@Override
			int position(int screenHeight, int size, int offset) {
				return (screenHeight - size) / 2 + offset;
			}
		},
		BOTTOM {
			@Override
			int position(int screenHeight, int size, int offset) {
				return screenHeight - size - offset;
			}
		};

		abstract int position(int screenHeight, int size, int offset);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
