package com.testmod.client;

import com.testmod.expression.ExpressionSnapshot;
import com.testmod.expression.ExpressionTextureSet;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class ExpressionFaceHud {
	private ExpressionFaceHud() {
	}

	static void register() {
		HudRenderCallback.EVENT.register(ExpressionFaceHud::render);
	}

	private static void render(GuiGraphics drawContext, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}

		FaceHudConfig.HudSettings settings = FaceHudConfig.snapshot();
		if (!settings.enabled()) {
			return;
		}

		int sourceSize = ExpressionTextureSet.TEXTURE_SIZE;
		int size = settings.pixelSize(sourceSize);
		int x = settings.corner().x(drawContext.guiWidth(), size, settings.offsetX());
		int y = settings.corner().y(drawContext.guiHeight(), size, settings.offsetY());

		drawTexture(drawContext, ExpressionFaceTextures.baseTextureFor(client.player.getUUID()), x, y, size, sourceSize, 1.0F);

		ExpressionSnapshot snapshot = ClientExpressionSync.localSnapshotForRender();
		if (FaceHudConfig.technicalDifficultiesFaceEnabled() && snapshot.technicalDifficulties()) {
			drawTexture(drawContext, ExpressionFaceTextures.technicalDifficultiesTexture(), x, y, size, sourceSize, 1.0F);
			return;
		}
		if (!snapshot.renderable()) {
			return;
		}

		float alpha = 0.70F + snapshot.confidence() * 0.30F;
		List<ResourceLocation> partTextures = ExpressionFaceTextures.partTexturesFor(client.player.getUUID(), snapshot.parts());
		if (partTextures.isEmpty()) {
			ResourceLocation expressionTexture = ExpressionFaceTextures.textureFor(client.player.getUUID(), snapshot.expression());
			drawTexture(drawContext, expressionTexture, x, y, size, sourceSize, alpha);
			return;
		}

		for (ResourceLocation partTexture : partTextures) {
			drawTexture(drawContext, partTexture, x, y, size, sourceSize, alpha);
		}
	}

	private static void drawTexture(GuiGraphics drawContext, ResourceLocation texture, int x, int y, int size, int sourceSize, float alpha) {
		drawContext.setColor(1.0F, 1.0F, 1.0F, alpha);
		drawContext.blit(texture, x, y, size, size, 0.0F, 0.0F, sourceSize, sourceSize, sourceSize, sourceSize);
		drawContext.setColor(1.0F, 1.0F, 1.0F, 1.0F);
	}
}
