package de.valentinlehmann.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
//? if newFabricRendering {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;*/
//?}
//? if newRendering {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import net.minecraft.client.renderer.RenderType;*/
//?}
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Paints the VLC-backed texture as a flat quad in the world using Minecraft's
 * {@code entityCutoutNoCull} render type (so it's unlit at full brightness and
 * visible from both sides).
 */
public final class VLCScreenRenderer {

	/** Full block + sky light, matching {@code LightTexture.FULL_BRIGHT}. */
	private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
	/** "No overlay" value — equivalent to {@code OverlayTexture.NO_OVERLAY}. */
	private static final int NO_OVERLAY = (10 << 16) | 0;

	private VLCScreenRenderer() {}

	public static void render(WorldRenderContext ctx) {
		net.minecraft.client.Camera camera = ctx.gameRenderer().getMainCamera();
		//? if newRendering {
		Vec3 cam = camera.position();
		VLCAudioManager.updateListener(cam.x, cam.y, cam.z, camera.yRot());
		//?} else {
		/*Vec3 cam = camera.getPosition();
		VLCAudioManager.updateListener(cam.x, cam.y, cam.z, camera.getYRot());*/
		//?}

		VLCScreenState.Config cfg = VLCScreenState.get();
		if (!cfg.enabled()) return;
		if (!VLCPlayerManager.uploadIfDirty()) return;
		//? if newFabricRendering {
		PoseStack matrices = ctx.matrices();
		//?} else {
		/*PoseStack matrices = ctx.matrixStack();*/
		//?}
		if (matrices == null) return;

		matrices.pushPose();
		matrices.translate(
				cfg.x() - cam.x,
				cfg.y() - cam.y,
				cfg.z() - cam.z);

		float w = cfg.width();
		float h = cfg.height();

		if (cfg.yaw() != 0f || cfg.pitch() != 0f || cfg.roll() != 0f) {
			Quaternionf rotation = new Quaternionf().rotationYXZ(
					(float) Math.toRadians(cfg.yaw()),
					(float) Math.toRadians(cfg.pitch()),
					(float) Math.toRadians(cfg.roll()));
			matrices.mulPose(rotation);
		}

		matrices.translate(-w / 2f, -h / 2f, 0f);

		Matrix4f matrix = matrices.last().pose();

		//? if newRendering {
		RenderType renderType = RenderTypes.entityCutoutNoCull(VLCPlayerManager.TEXTURE_ID);
		//?} else {
		/*RenderType renderType = RenderType.entityCutoutNoCull(VLCPlayerManager.TEXTURE_ID);*/
		//?}

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(renderType.mode(), renderType.format());

		addVertex(buffer, matrix, 0f, 0f, 0f, 0f, 1f);
		addVertex(buffer, matrix, w,  0f, 0f, 1f, 1f);
		addVertex(buffer, matrix, w,  h,  0f, 1f, 0f);
		addVertex(buffer, matrix, 0f, h,  0f, 0f, 0f);

		try (MeshData mesh = buffer.buildOrThrow()) {
			renderType.draw(mesh);
		}

		matrices.popPose();
	}

	private static void addVertex(BufferBuilder buffer, Matrix4f matrix,
			float x, float y, float z, float u, float v) {
		buffer.addVertex(matrix, x, y, z)
				.setColor(0xFFFFFFFF)
				.setUv(u, v)
				.setOverlay(NO_OVERLAY)
				.setLight(FULL_BRIGHT_LIGHT)
				.setNormal(0f, 0f, 1f);
	}
}
