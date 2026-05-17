package com.testmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.testmod.expression.ExpressionSnapshot;
import java.util.List;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

final class ExpressionFaceLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static final float FACE_MIN = -4.05F / 16.0F;
	private static final float FACE_MAX = 4.05F / 16.0F;
	private static final float FACE_TOP = -8.05F / 16.0F;
	private static final float FACE_BOTTOM = 0.05F / 16.0F;
	private static final float BASE_FACE_Z = -4.11F / 16.0F;
	private static final float EXPRESSION_FACE_Z = -4.14F / 16.0F;

	ExpressionFaceLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	@Override
	public void render(
			PoseStack poseStack,
			MultiBufferSource bufferSource,
			int packedLight,
			AbstractClientPlayer player,
			float limbSwing,
			float limbSwingAmount,
			float partialTick,
			float ageInTicks,
			float netHeadYaw,
			float headPitch
	) {
		if (player.isInvisible()) {
			return;
		}

		boolean localPlayer = isLocalPlayer(player);
		ExpressionSnapshot snapshot = localPlayer ? ClientExpressionSync.localSnapshotForRender() : ClientExpressionStore.get(player.getUUID()).orElse(null);
		boolean renderExpression = snapshot != null && snapshot.renderable();
		boolean renderTechnicalDifficulties = FaceHudConfig.technicalDifficultiesFaceEnabled()
				&& snapshot != null
				&& snapshot.technicalDifficulties();
		boolean hasPlayerTextures = ExpressionFaceTextures.hasPlayerTextures(player.getUUID());
		boolean renderBaseTexture = hasPlayerTextures || localPlayer;
		if (!renderExpression && !renderTechnicalDifficulties && !renderBaseTexture) {
			return;
		}

		poseStack.pushPose();
		getParentModel().head.translateAndRotate(poseStack);
		PoseStack.Pose pose = poseStack.last();
		if (renderBaseTexture) {
			ResourceLocation baseTexture = ExpressionFaceTextures.baseTextureFor(player.getUUID());
			renderFaceTexture(bufferSource, pose, baseTexture, BASE_FACE_Z, packedLight, 255);
		}
		if (renderTechnicalDifficulties) {
			renderFaceTexture(bufferSource, pose, ExpressionFaceTextures.technicalDifficultiesTexture(), EXPRESSION_FACE_Z, packedLight, 255);
			poseStack.popPose();
			return;
		}
		if (renderExpression) {
			int alpha = Math.round(180.0F + snapshot.confidence() * 75.0F);
			List<ResourceLocation> partTextures = ExpressionFaceTextures.partTexturesFor(player.getUUID(), snapshot.parts());
			if (partTextures.isEmpty()) {
				ResourceLocation expressionTexture = ExpressionFaceTextures.textureFor(player.getUUID(), snapshot.expression());
				renderFaceTexture(bufferSource, pose, expressionTexture, EXPRESSION_FACE_Z, packedLight, alpha);
			} else {
				for (int index = 0; index < partTextures.size(); index++) {
					renderFaceTexture(bufferSource, pose, partTextures.get(index), EXPRESSION_FACE_Z - index * 0.003F, packedLight, alpha);
				}
			}
		}
		poseStack.popPose();
	}

	private static boolean isLocalPlayer(AbstractClientPlayer player) {
		Minecraft client = Minecraft.getInstance();
		return client.player != null && client.player.getUUID().equals(player.getUUID());
	}

	private static void renderFaceTexture(MultiBufferSource bufferSource, PoseStack.Pose pose, ResourceLocation texture, float z, int packedLight, int alpha) {
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
		vertex(consumer, pose, FACE_MIN, FACE_BOTTOM, z, 0.0F, 1.0F, packedLight, alpha);
		vertex(consumer, pose, FACE_MAX, FACE_BOTTOM, z, 1.0F, 1.0F, packedLight, alpha);
		vertex(consumer, pose, FACE_MAX, FACE_TOP, z, 1.0F, 0.0F, packedLight, alpha);
		vertex(consumer, pose, FACE_MIN, FACE_TOP, z, 0.0F, 0.0F, packedLight, alpha);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light, int alpha) {
		consumer.addVertex(pose, x, y, z)
				.setColor(255, 255, 255, alpha)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, 0.0F, 0.0F, -1.0F);
	}
}
