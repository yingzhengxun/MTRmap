package com.mtrmap.client.xaero;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
//? if >=1.20.1 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
//?} else if >=1.17 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.renderer.GameRenderer;
*///?} else {
/*// 1.16.5 没有 BufferUploader / RenderSystem.setShader，用的是老的即时模式 Tesselator
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Matrix4f;
*///?}

/**
 * 版本无关的 GUI 绘制入口。
 *
 * 1.20.1 起有 {@code GuiGraphics}，它已经打包了 PoseStack、MultiBufferSource 与文字绘制；
 * 1.19.2 没有这个类，只能拿到 {@code PoseStack}，所以这里用一个薄封装把差异挡在内部，
 * 让 {@link OverlayRenderer} 与 Xaero 叠加层 mixin 的绘制代码在两端共用同一份。
 *
 * 调用约定：先连续写四边形，最后 {@link #flush()} 一次统一提交；
 * 文字绘制（{@link #text}）会自行保证之前排队的四边形已经提交。
 */
public final class GuiSink {

	//? if >=1.20.1 {
	private final GuiGraphics gg;

	public GuiSink(GuiGraphics gg) {
		this.gg = gg;
	}

	public Matrix4f matrix() {
		return gg.pose().last().pose();
	}

	public VertexConsumer vertices() {
		return gg.bufferSource().getBuffer(RenderType.gui());
	}

	public void flush() {
		gg.flush();
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		gg.fill(x1, y1, x2, y2, color);
	}

	public void push() {
		gg.pose().pushPose();
	}

	public void translate(float x, float y, float z) {
		gg.pose().translate(x, y, z);
	}

	public void scale(float s) {
		gg.pose().scale(s, s, 1f);
	}

	public void pop() {
		gg.pose().popPose();
	}

	/** 以 {@code centerX} 为水平中心画一行文字 */
	public void text(Font font, String text, float centerX, float y, int color, boolean shadow) {
		gg.drawString(font, text, Math.round(centerX - font.width(text) / 2f), Math.round(y), color, shadow);
	}
	//?} else if >=1.19 {
	/*private final PoseStack pose;
	// 延迟创建的顶点缓冲：第一次要画四边形时才把渲染状态设好
	private BufferBuilder builder;

	public GuiSink(PoseStack pose) {
		this.pose = pose;
	}

	public Matrix4f matrix() {
		return pose.last().pose();
	}

	public VertexConsumer vertices() {
		if (builder == null) {
			// 1.19.2 没有 GuiGraphics，这里手动复刻 Gui.fill 的那套状态：
			// 混合开、纹理关（顶点格式没有 UV）、不剔除背面，着色器用 POSITION_COLOR。
			RenderSystem.enableBlend();
			RenderSystem.disableTexture();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShader(GameRenderer::getPositionColorShader);
			RenderSystem.disableCull();
			builder = Tesselator.getInstance().getBuilder();
			builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		}
		return builder;
	}

	public void flush() {
		if (builder == null) {
			return;
		}
		BufferUploader.draw(builder.end());
		builder = null;
		RenderSystem.enableTexture();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		float a = ((color >>> 24) & 0xFF) / 255f;
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		Matrix4f m = matrix();
		VertexConsumer vc = vertices();
		vc.vertex(m, x1, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y1, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x1, y1, 0f).color(r, g, b, a).endVertex();
	}

	public void push() {
		pose.pushPose();
	}

	public void translate(float x, float y, float z) {
		pose.translate(x, y, z);
	}

	public void scale(float s) {
		pose.scale(s, s, 1f);
	}

	public void pop() {
		pose.popPose();
	}

	// 以 {@code centerX} 为水平中心画一行文字
	public void text(Font font, String text, float centerX, float y, int color, boolean shadow) {
		// 文字用的是另一套渲染状态，先把已排队的四边形提交掉
		flush();
		float x = centerX - font.width(text) / 2f;
		if (shadow) {
			font.drawShadow(pose, text, x, y, color);
		} else {
			font.draw(pose, text, x, y, color);
		}
	}
	*///?} else if >=1.17 {
	/*private final PoseStack pose;
	// 延迟创建的顶点缓冲：第一次要画四边形时才把渲染状态设好
	private BufferBuilder builder;

	public GuiSink(PoseStack pose) {
		this.pose = pose;
	}

	public Matrix4f matrix() {
		return pose.last().pose();
	}

	public VertexConsumer vertices() {
		if (builder == null) {
			// 1.18.2 还没有 GuiGraphics，手动复刻 Gui.fill 的那套状态：
			// 混合开、纹理关（顶点格式没有 UV）、不剔除背面，着色器用 POSITION_COLOR。
			RenderSystem.enableBlend();
			RenderSystem.disableTexture();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShader(GameRenderer::getPositionColorShader);
			RenderSystem.disableCull();
			builder = Tesselator.getInstance().getBuilder();
			builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		}
		return builder;
	}

	public void flush() {
		if (builder == null) {
			return;
		}
		// 1.18.2 的 BufferBuilder.end() 返回 void，且还没有 BufferUploader.draw：
		// 先 end 结束顶点写入，再交给 BufferUploader.end 提交
		builder.end();
		BufferUploader.end(builder);
		builder = null;
		RenderSystem.enableTexture();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		float a = ((color >>> 24) & 0xFF) / 255f;
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		Matrix4f m = matrix();
		VertexConsumer vc = vertices();
		vc.vertex(m, x1, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y1, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x1, y1, 0f).color(r, g, b, a).endVertex();
	}

	public void push() {
		pose.pushPose();
	}

	public void translate(float x, float y, float z) {
		pose.translate(x, y, z);
	}

	public void scale(float s) {
		pose.scale(s, s, 1f);
	}

	public void pop() {
		pose.popPose();
	}

	// 以 {@code centerX} 为水平中心画一行文字
	public void text(Font font, String text, float centerX, float y, int color, boolean shadow) {
		// 文字用的是另一套渲染状态，先把已排队的四边形提交掉
		flush();
		float x = centerX - font.width(text) / 2f;
		if (shadow) {
			font.drawShadow(pose, text, x, y, color);
		} else {
			font.draw(pose, text, x, y, color);
		}
	}
	*///?} else {
	/*private final PoseStack pose;
	// 延迟创建的顶点缓冲：第一次要画四边形时才把渲染状态设好
	private BufferBuilder builder;

	public GuiSink(PoseStack pose) {
		this.pose = pose;
	}

	public Matrix4f matrix() {
		return pose.last().pose();
	}

	public VertexConsumer vertices() {
		if (builder == null) {
			// 1.16.5 还是老的即时模式 Tesselator：混合开、纹理关、不剔除背面。
			// 这时候还没有 RenderSystem.setShader / BufferUploader，顶点格式自带着色器状态。
			RenderSystem.enableBlend();
			RenderSystem.disableTexture();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableCull();
			builder = Tesselator.getInstance().getBuilder();
			// 1.16.5 还没有 VertexFormat.Mode，begin 直接收 GL 模式常量（QUADS = 7）
			builder.begin(7, DefaultVertexFormat.POSITION_COLOR);
		}
		return builder;
	}

	public void flush() {
		if (builder == null) {
			return;
		}
		// 老版 Tesselator 由它自己 end() 提交缓冲
		Tesselator.getInstance().end();
		builder = null;
		RenderSystem.enableTexture();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		float a = ((color >>> 24) & 0xFF) / 255f;
		float r = ((color >> 16) & 0xFF) / 255f;
		float g = ((color >> 8) & 0xFF) / 255f;
		float b = (color & 0xFF) / 255f;
		Matrix4f m = matrix();
		VertexConsumer vc = vertices();
		vc.vertex(m, x1, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y2, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x2, y1, 0f).color(r, g, b, a).endVertex();
		vc.vertex(m, x1, y1, 0f).color(r, g, b, a).endVertex();
	}

	public void push() {
		pose.pushPose();
	}

	public void translate(float x, float y, float z) {
		pose.translate(x, y, z);
	}

	public void scale(float s) {
		pose.scale(s, s, 1f);
	}

	public void pop() {
		pose.popPose();
	}

	// 以 {@code centerX} 为水平中心画一行文字
	public void text(Font font, String text, float centerX, float y, int color, boolean shadow) {
		// 文字用的是另一套渲染状态，先把已排队的四边形提交掉
		flush();
		float x = centerX - font.width(text) / 2f;
		if (shadow) {
			font.drawShadow(pose, text, x, y, color);
		} else {
			font.draw(pose, text, x, y, color);
		}
	}
	*///?}
}
