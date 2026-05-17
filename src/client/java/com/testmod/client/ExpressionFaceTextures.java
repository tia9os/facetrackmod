package com.testmod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.testmod.TestMod;
import com.testmod.expression.ExpressionKind;
import com.testmod.expression.ExpressionParts;
import com.testmod.expression.ExpressionTextureSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

final class ExpressionFaceTextures {
	private static final int SIZE = ExpressionTextureSet.TEXTURE_SIZE;
	private static final String DIRECTORY_NAME = "expressions";
	private static final String BASE_TEXTURE_NAME = ExpressionTextureSet.BASE_TEXTURE_NAME;
	private static final int TRANSPARENT = 0x00000000;
	private static final int BLACK = 0xFF000000;
	private static final int BASE = 0xFFE0E0E0;
	private static final ResourceLocation TECHNICAL_DIFFICULTIES_TEXTURE = ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "textures/expression/technical_difficulties.png");
	private static final Map<ExpressionKind, ResourceLocation> TEXTURES = new EnumMap<>(ExpressionKind.class);
	private static final Map<String, ResourceLocation> PART_TEXTURES = new LinkedHashMap<>();
	private static final ConcurrentMap<UUID, PlayerTextureSet> PLAYER_TEXTURES = new ConcurrentHashMap<>();
	private static final AtomicInteger TEXTURE_VERSION = new AtomicInteger();
	private static ResourceLocation baseTexture;
	private static boolean registered;

	private ExpressionFaceTextures() {
	}

	static ResourceLocation textureFor(ExpressionKind expression) {
		if (!registered) {
			register(Minecraft.getInstance().getTextureManager());
		}
		return TEXTURES.getOrDefault(expression, TEXTURES.get(ExpressionKind.NEUTRAL));
	}

	static ResourceLocation textureFor(UUID playerId, ExpressionKind expression) {
		if (!registered) {
			register(Minecraft.getInstance().getTextureManager());
		}

		PlayerTextureSet textures = playerId == null ? null : PLAYER_TEXTURES.get(playerId);
		if (textures != null) {
			ResourceLocation texture = textures.textureFor(expression);
			if (texture != null) {
				return texture;
			}
		}
		return textureFor(expression);
	}

	static ResourceLocation baseTexture() {
		if (!registered) {
			register(Minecraft.getInstance().getTextureManager());
		}
		return baseTexture;
	}

	static ResourceLocation technicalDifficultiesTexture() {
		return TECHNICAL_DIFFICULTIES_TEXTURE;
	}

	static List<ResourceLocation> partTexturesFor(UUID playerId, ExpressionParts parts) {
		if (!registered) {
			register(Minecraft.getInstance().getTextureManager());
		}
		ensurePartTexturesRegistered();
		if (parts == null) {
			return List.of();
		}

		String[] textureNames = parts.textureNames();
		PlayerTextureSet textures = playerId == null ? null : PLAYER_TEXTURES.get(playerId);
		if (textures != null) {
			return completePartTextures(textures.partTextures(), PART_TEXTURES, textureNames);
		}
		return completePartTextures(PART_TEXTURES, Map.of(), textureNames);
	}

	static ResourceLocation baseTextureFor(UUID playerId) {
		if (!registered) {
			register(Minecraft.getInstance().getTextureManager());
		}

		PlayerTextureSet textures = playerId == null ? null : PLAYER_TEXTURES.get(playerId);
		if (textures != null && textures.baseTexture() != null) {
			return textures.baseTexture();
		}
		return baseTexture;
	}

	static void applyPlayerTextures(UUID playerId, ExpressionTextureSet textureSet) {
		if (playerId == null) {
			return;
		}
		if (textureSet == null || textureSet.isEmpty()) {
			PLAYER_TEXTURES.remove(playerId);
			return;
		}

		Minecraft client = Minecraft.getInstance();
		TextureManager textureManager = client.getTextureManager();
		if (!registered) {
			register(textureManager);
		}

		int version = TEXTURE_VERSION.incrementAndGet();
		ResourceLocation playerBaseTexture = registerPlayerTexture(textureManager, playerId, BASE_TEXTURE_NAME, textureSet.texture(BASE_TEXTURE_NAME), version);
		Map<ExpressionKind, ResourceLocation> playerExpressionTextures = new EnumMap<>(ExpressionKind.class);
		for (ExpressionKind expression : ExpressionKind.values()) {
			if (!expression.renderable()) {
				continue;
			}

			ResourceLocation texture = registerPlayerTexture(textureManager, playerId, expression.wireName(), textureSet.texture(expression.wireName()), version);
			if (texture != null) {
				playerExpressionTextures.put(expression, texture);
			}
		}
		Map<String, ResourceLocation> playerPartTextures = new LinkedHashMap<>();
		for (String textureName : ExpressionTextureSet.PART_TEXTURE_NAMES) {
			ResourceLocation texture = registerPlayerTexture(textureManager, playerId, textureName, textureSet.texture(textureName), version);
			if (texture != null) {
				playerPartTextures.put(textureName, texture);
			}
		}
		PLAYER_TEXTURES.put(playerId, new PlayerTextureSet(playerBaseTexture, playerExpressionTextures, playerPartTextures));
	}

	static void clearPlayerTextures() {
		PLAYER_TEXTURES.clear();
	}

	static boolean hasPlayerTextures(UUID playerId) {
		return playerId != null && PLAYER_TEXTURES.containsKey(playerId);
	}

	static ExpressionTextureSet readLocalTextureSet() {
		Path textureDirectory = textureDirectory();
		try {
			Files.createDirectories(textureDirectory);
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not create face expression texture folder '{}'.", textureDirectory, exception);
			return ExpressionTextureSet.EMPTY;
		}

		Map<String, byte[]> textures = new LinkedHashMap<>();
		for (String textureName : ExpressionTextureSet.LEGACY_TEXTURE_NAMES) {
			Path texturePath = textureDirectory.resolve(textureName + ".png");
			ensureTextureFile(texturePath, defaultTextureFactory(textureName));
			try {
				long fileSize = Files.size(texturePath);
				if (fileSize <= 0 || fileSize > ExpressionTextureSet.MAX_TEXTURE_BYTES) {
					TestMod.LOGGER.warn("Skipping face expression texture '{}' because it is {} bytes. Limit is {} bytes.", texturePath, fileSize, ExpressionTextureSet.MAX_TEXTURE_BYTES);
					continue;
				}
				textures.put(textureName, Files.readAllBytes(texturePath));
			} catch (IOException exception) {
				TestMod.LOGGER.warn("Could not read face expression texture '{}'.", texturePath, exception);
			}
		}
		if (partsDirectoryEnabled(textureDirectory)) {
			for (String textureName : ExpressionTextureSet.PART_TEXTURE_NAMES) {
				Path texturePath = textureDirectory.resolve(textureName + ".png");
				ensureTextureFile(texturePath, defaultTextureFactory(textureName));
				try {
					long fileSize = Files.size(texturePath);
					if (fileSize <= 0 || fileSize > ExpressionTextureSet.MAX_TEXTURE_BYTES) {
						TestMod.LOGGER.warn("Skipping face part texture '{}' because it is {} bytes. Limit is {} bytes.", texturePath, fileSize, ExpressionTextureSet.MAX_TEXTURE_BYTES);
						continue;
					}
					textures.put(textureName, Files.readAllBytes(texturePath));
				} catch (IOException exception) {
					TestMod.LOGGER.warn("Could not read face part texture '{}'.", texturePath, exception);
				}
			}
		}
		return new ExpressionTextureSet(textures);
	}

	static void register(TextureManager textureManager) {
		if (registered) {
			return;
		}

		Path textureDirectory = textureDirectory();
		try {
			Files.createDirectories(textureDirectory);
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not create face expression texture folder '{}'. Generated in-memory textures will be used.", textureDirectory, exception);
		}

		baseTexture = ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "dynamic/expression/" + BASE_TEXTURE_NAME);
		textureManager.register(baseTexture, new DynamicTexture(loadTexture(textureDirectory, BASE_TEXTURE_NAME, ExpressionFaceTextures::createDefaultBaseTexture)));

		for (ExpressionKind expression : ExpressionKind.values()) {
			if (!expression.renderable()) {
				continue;
			}

			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "dynamic/expression/" + expression.wireName());
			textureManager.register(id, new DynamicTexture(loadTexture(textureDirectory, expression.wireName(), () -> createDefaultTexture(expression))));
			TEXTURES.put(expression, id);
		}
		registerPartTexturesIfEnabled(textureManager, textureDirectory);
		registered = true;
	}

	private static Path textureDirectory() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(DIRECTORY_NAME);
	}

	private static void ensureTextureFile(Path texturePath, Supplier<NativeImage> fallbackFactory) {
		if (Files.isRegularFile(texturePath)) {
			return;
		}

		NativeImage fallback = fallbackFactory.get();
		try {
			Path parent = texturePath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			fallback.writeToFile(texturePath);
			TestMod.LOGGER.info("Created default face expression texture at {}", texturePath);
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not save default face expression texture '{}'.", texturePath, exception);
		} finally {
			fallback.close();
		}
	}

	private static NativeImage loadTexture(Path textureDirectory, String textureName, Supplier<NativeImage> fallbackFactory) {
		Path texturePath = textureDirectory.resolve(textureName + ".png");
		if (!Files.isRegularFile(texturePath)) {
			NativeImage fallback = fallbackFactory.get();
			try {
				Path parent = texturePath.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				fallback.writeToFile(texturePath);
				TestMod.LOGGER.info("Created default {} face expression texture at {}", textureName, texturePath);
			} catch (IOException exception) {
				TestMod.LOGGER.warn("Could not save default face expression texture '{}'.", texturePath, exception);
			}
			return fallback;
		}

		try (InputStream inputStream = Files.newInputStream(texturePath)) {
			NativeImage loaded = NativeImage.read(inputStream);
			if (loaded.getWidth() == SIZE && loaded.getHeight() == SIZE) {
				if (BASE_TEXTURE_NAME.equals(textureName) && isGeneratedDefaultBaseTexture(loaded)) {
					loaded.close();
					return createDefaultBaseTexture();
				}
				return loaded;
			}

			TestMod.LOGGER.warn(
					"Face expression texture '{}' is {}x{}; scaling it to {}x{} in memory.",
					texturePath,
					loaded.getWidth(),
					loaded.getHeight(),
					SIZE,
					SIZE
			);
			NativeImage resized = resizeNearest(loaded);
			loaded.close();
			if (BASE_TEXTURE_NAME.equals(textureName) && isGeneratedDefaultBaseTexture(resized)) {
				resized.close();
				return createDefaultBaseTexture();
			}
			return resized;
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not load face expression texture '{}'. Generated in-memory texture will be used.", texturePath, exception);
			return fallbackFactory.get();
		}
	}

	private static ResourceLocation registerPlayerTexture(TextureManager textureManager, UUID playerId, String textureName, byte[] bytes, int version) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}

		try {
			NativeImage image = NativeImage.read(bytes);
			if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
				NativeImage resized = resizeNearest(image);
				image.close();
				image = resized;
			}
			if (BASE_TEXTURE_NAME.equals(textureName) && isGeneratedDefaultBaseTexture(image)) {
				image.close();
				image = createDefaultBaseTexture();
			}

			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
					TestMod.MOD_ID,
					"dynamic/player_expression/" + playerId + "/" + version + "/" + textureName
			);
			textureManager.register(id, new DynamicTexture(image));
			return id;
		} catch (IOException exception) {
			TestMod.LOGGER.warn("Could not register synced face expression texture '{}' for player {}.", textureName, playerId, exception);
			return null;
		}
	}

	private static NativeImage resizeNearest(NativeImage source) {
		NativeImage resized = new NativeImage(SIZE, SIZE, true);
		int sourceWidth = source.getWidth();
		int sourceHeight = source.getHeight();
		for (int y = 0; y < SIZE; y++) {
			int sourceY = Math.min(sourceHeight - 1, y * sourceHeight / SIZE);
			for (int x = 0; x < SIZE; x++) {
				int sourceX = Math.min(sourceWidth - 1, x * sourceWidth / SIZE);
				resized.setPixelRGBA(x, y, source.getPixelRGBA(sourceX, sourceY));
			}
		}
		return resized;
	}

	private static boolean partsDirectoryEnabled(Path textureDirectory) {
		return Files.isDirectory(textureDirectory.resolve("parts"));
	}

	private static void ensurePartTexturesRegistered() {
		if (!registered || !PART_TEXTURES.isEmpty()) {
			return;
		}

		Path textureDirectory = textureDirectory();
		if (partsDirectoryEnabled(textureDirectory)) {
			registerPartTexturesIfEnabled(Minecraft.getInstance().getTextureManager(), textureDirectory);
		}
	}

	private static void registerPartTexturesIfEnabled(TextureManager textureManager, Path textureDirectory) {
		if (!partsDirectoryEnabled(textureDirectory)) {
			return;
		}

		for (String textureName : ExpressionTextureSet.PART_TEXTURE_NAMES) {
			if (PART_TEXTURES.containsKey(textureName)) {
				continue;
			}

			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "dynamic/expression/" + textureName);
			textureManager.register(id, new DynamicTexture(loadTexture(textureDirectory, textureName, defaultTextureFactory(textureName))));
			PART_TEXTURES.put(textureName, id);
		}
	}

	private static List<ResourceLocation> completePartTextures(
			Map<String, ResourceLocation> textures,
			Map<String, ResourceLocation> fallbackTextures,
			String[] textureNames
	) {
		List<ResourceLocation> result = new ArrayList<>(textureNames.length);
		for (String textureName : textureNames) {
			ResourceLocation texture = textures.get(textureName);
			if (texture == null) {
				texture = fallbackTextures.get(textureName);
			}
			if (texture == null) {
				return List.of();
			}
			result.add(texture);
		}
		return List.copyOf(result);
	}

	private static Supplier<NativeImage> defaultTextureFactory(String textureName) {
		if (BASE_TEXTURE_NAME.equals(textureName)) {
			return ExpressionFaceTextures::createDefaultBaseTexture;
		}
		if (ExpressionTextureSet.PART_TEXTURE_NAMES.contains(textureName)) {
			return () -> createDefaultPartTexture(textureName);
		}

		ExpressionKind expression = ExpressionKind.fromWireName(textureName);
		return () -> createDefaultTexture(expression);
	}

	private static NativeImage createDefaultBaseTexture() {
		NativeImage image = new NativeImage(SIZE, SIZE, true);
		fill(image, TRANSPARENT);
		return image;
	}

	private static boolean isGeneratedDefaultBaseTexture(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if (image.getPixelRGBA(x, y) != BASE) {
					return false;
				}
			}
		}
		return true;
	}

	private static NativeImage createDefaultPartTexture(String textureName) {
		NativeImage image = new NativeImage(SIZE, SIZE, true);
		fill(image, TRANSPARENT);

		switch (textureName) {
			case "parts/mouth/happy" -> {
				drawLine(image, 2, 5, 3, 6, BLACK);
				drawLine(image, 3, 6, 4, 6, BLACK);
				drawLine(image, 4, 6, 5, 5, BLACK);
			}
			case "parts/mouth/sad" -> {
				drawLine(image, 2, 6, 3, 5, BLACK);
				drawLine(image, 3, 5, 4, 5, BLACK);
				drawLine(image, 4, 5, 5, 6, BLACK);
			}
			case "parts/mouth/surprised" -> drawRect(image, 3, 4, 4, 6, BLACK);
			case "parts/mouth/talking" -> fillRect(image, 2, 5, 5, 6, BLACK);
			case "parts/mouth/funny" -> {
				fillRect(image, 2, 5, 5, 5, BLACK);
				drawLine(image, 3, 6, 4, 6, BLACK);
			}
			case "parts/mouth/neutral" -> drawLine(image, 2, 5, 5, 5, BLACK);
			case "parts/left_eye/open" -> setPixel(image, 2, 2, BLACK);
			case "parts/right_eye/open" -> setPixel(image, 5, 2, BLACK);
			case "parts/left_eye/closed", "parts/left_eye/focused" -> drawLine(image, 1, 2, 2, 2, BLACK);
			case "parts/right_eye/closed", "parts/right_eye/focused" -> drawLine(image, 5, 2, 6, 2, BLACK);
			case "parts/left_eyebrow/raised" -> drawLine(image, 1, 1, 3, 1, BLACK);
			case "parts/right_eyebrow/raised" -> drawLine(image, 4, 1, 6, 1, BLACK);
			case "parts/left_eyebrow/sad", "parts/left_eyebrow/focused" -> drawLine(image, 1, 1, 3, 2, BLACK);
			case "parts/right_eyebrow/sad", "parts/right_eyebrow/focused" -> drawLine(image, 4, 2, 6, 1, BLACK);
			default -> {
			}
		}

		return image;
	}

	private static NativeImage createDefaultTexture(ExpressionKind expression) {
		NativeImage image = new NativeImage(SIZE, SIZE, true);
		fill(image, TRANSPARENT);

		switch (expression) {
			case HAPPY -> {
				drawOpenEyes(image);
				drawLine(image, 2, 5, 3, 6, BLACK);
				drawLine(image, 3, 6, 4, 6, BLACK);
				drawLine(image, 4, 6, 5, 5, BLACK);
			}
			case SAD -> {
				drawLine(image, 1, 1, 3, 2, BLACK);
				drawLine(image, 4, 2, 6, 1, BLACK);
				drawSmallEyes(image);
				drawLine(image, 2, 6, 3, 5, BLACK);
				drawLine(image, 3, 5, 4, 5, BLACK);
				drawLine(image, 4, 5, 5, 6, BLACK);
			}
			case SURPRISED -> {
				drawOpenEyes(image);
				drawRect(image, 3, 4, 4, 6, BLACK);
			}
			case TALKING -> {
				drawOpenEyes(image);
				fillRect(image, 2, 5, 5, 6, BLACK);
			}
			case BLINKING -> {
				drawLine(image, 1, 2, 2, 2, BLACK);
				drawLine(image, 5, 2, 6, 2, BLACK);
				drawLine(image, 2, 5, 5, 5, BLACK);
			}
			case WINKING -> {
				drawLine(image, 1, 2, 2, 2, BLACK);
				setPixel(image, 5, 2, BLACK);
				drawLine(image, 2, 5, 3, 6, BLACK);
				drawLine(image, 3, 6, 4, 6, BLACK);
				drawLine(image, 4, 6, 5, 5, BLACK);
			}
			case FOCUSED -> {
				drawLine(image, 1, 1, 3, 2, BLACK);
				drawLine(image, 4, 2, 6, 1, BLACK);
				drawLine(image, 1, 2, 2, 2, BLACK);
				drawLine(image, 5, 2, 6, 2, BLACK);
				drawLine(image, 2, 5, 5, 5, BLACK);
			}
			case FUNNY -> {
				drawLine(image, 1, 1, 3, 1, BLACK);
				drawLine(image, 4, 1, 6, 1, BLACK);
				drawLine(image, 1, 2, 2, 2, BLACK);
				setPixel(image, 5, 2, BLACK);
				fillRect(image, 2, 5, 5, 5, BLACK);
				drawLine(image, 3, 6, 4, 6, BLACK);
			}
			case NEUTRAL -> {
				drawOpenEyes(image);
				drawLine(image, 2, 5, 5, 5, BLACK);
			}
			default -> {
			}
		}

		return image;
	}

	private static void fill(NativeImage image, int color) {
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				image.setPixelRGBA(x, y, color);
			}
		}
	}

	private static void drawOpenEyes(NativeImage image) {
		setPixel(image, 2, 2, BLACK);
		setPixel(image, 5, 2, BLACK);
	}

	private static void drawSmallEyes(NativeImage image) {
		drawLine(image, 1, 2, 2, 2, BLACK);
		drawLine(image, 5, 2, 6, 2, BLACK);
	}

	private static void fillRect(NativeImage image, int minX, int minY, int maxX, int maxY, int color) {
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				setPixel(image, x, y, color);
			}
		}
	}

	private static void drawRect(NativeImage image, int minX, int minY, int maxX, int maxY, int color) {
		drawLine(image, minX, minY, maxX, minY, color);
		drawLine(image, maxX, minY, maxX, maxY, color);
		drawLine(image, maxX, maxY, minX, maxY, color);
		drawLine(image, minX, maxY, minX, minY, color);
	}

	private static void drawLine(NativeImage image, int startX, int startY, int endX, int endY, int color) {
		int dx = Math.abs(endX - startX);
		int dy = -Math.abs(endY - startY);
		int sx = startX < endX ? 1 : -1;
		int sy = startY < endY ? 1 : -1;
		int error = dx + dy;
		int x = startX;
		int y = startY;

		while (true) {
			setPixel(image, x, y, color);
			if (x == endX && y == endY) {
				return;
			}

			int doubledError = error * 2;
			if (doubledError >= dy) {
				error += dy;
				x += sx;
			}
			if (doubledError <= dx) {
				error += dx;
				y += sy;
			}
		}
	}

	private static void setPixel(NativeImage image, int x, int y, int color) {
		if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
			image.setPixelRGBA(x, y, color);
		}
	}

	private record PlayerTextureSet(
			ResourceLocation baseTexture,
			Map<ExpressionKind, ResourceLocation> expressionTextures,
			Map<String, ResourceLocation> partTextures
	) {
		private ResourceLocation textureFor(ExpressionKind expression) {
			return expressionTextures.get(expression);
		}
	}
}
