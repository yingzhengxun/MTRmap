package com.mtrmap.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mtrmap.MtrMapCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 游戏内毛玻璃的「背景模糊」。
 *
 * <p>网页端有 CSS {@code backdrop-filter}，游戏内没有，所以这里自己来：
 * 每帧把已经画好的画面（地图线网 / 车站 / 列车）从主 framebuffer 抓一份，
 * 先用 GPU 缩小到 GUI 像素尺寸，再在 CPU 上做类似高斯的两遍盒式模糊，
 * 最后贴成一张动态纹理，面板绘制时按圆角逐行采样 —— 就得到了「透过玻璃、背景被模糊」的效果。
 *
 * <p>抓取按 50ms 节流，只在毛玻璃模式下、且上一帧确实画过毛玻璃卡片时才调用；
 * 任何一步失败（驱动不支持 / 状态异常）就把 {@link #isAvailable()} 置为 false，
 * 调用方退回半透明实色，不影响正常游玩。
 */
public final class GlassBackdrop {

	/** 面板采样用的动态纹理位置 */
	//? if >=1.21.1 {
	/*private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath("rbmm", "glass_backdrop");
	*///?} else {
	private static final ResourceLocation LOCATION = new ResourceLocation("rbmm", "glass_backdrop");
	//?}
	/** 抓取间隔（纳秒）：抓屏 + CPU 模糊很贵，50ms 一次（约 20fps 更新）已经看不出卡顿 */
	private static final long CAPTURE_INTERVAL = 50_000_000L;

	private static int framebuffer = -1;
	private static int texture = -1;
	private static AbstractTexture textureHandle;
	private static int texWidth;
	private static int texHeight;
	/** 抓到但还没处理（ABGR，行序为 GL 的下→上） */
	private static int[] raw;
	/** 加了液态扰动之后 */
	private static int[] warped;
	/** 模糊往返用的两块缓冲 */
	private static int[] blurA;
	private static int[] blurB;
	private static ByteBuffer readBuffer;
	private static ByteBuffer uploadBuffer;
	private static boolean ready;
	private static boolean broken;
	private static long lastCapture;

	private GlassBackdrop() {
	}

	public static boolean isAvailable() {
		return ready && !broken;
	}

	/**
	 * 抓取当前画面并生成模糊背景。调用前必须确保之前排队的 GUI 四边形已经提交（{@code sink.flush()}）。
	 */
	public static void capture(int guiWidth, int guiHeight) {
		if (broken || guiWidth < 4 || guiHeight < 4) {
			return;
		}
		long now = System.nanoTime();
		if (ready && now - lastCapture < CAPTURE_INTERVAL) {
			return;
		}
		try {
			Minecraft client = Minecraft.getInstance();
			Window window = client.getWindow();
			int fbWidth = window.getWidth();
			int fbHeight = window.getHeight();
			double guiScale = Math.max(1.0, window.getGuiScale());
			if (fbWidth < 4 || fbHeight < 4) {
				return;
			}
			if (texture == -1 || texWidth != guiWidth || texHeight != guiHeight) {
				resize(client, guiWidth, guiHeight);
			}
			readPixels(fbWidth, fbHeight);
			process(System.nanoTime() / 1_000_000_000.0, guiScale);
			upload();
			lastCapture = now;
			ready = true;
		} catch (Throwable error) {
			broken = true;
			ready = false;
			MtrMapCommon.LOGGER.warn("毛玻璃背景抓取失败，游戏内将退回半透明填充", error);
		}
	}

	/**
	 * 把模糊背景画进矩形区域，圆角处逐行收缩（相当于给背景加圆角遮罩）。
	 *
	 * @return 是否真的画上了；false 时调用方需要自己铺一层半透明底色
	 */
	public static boolean draw(GuiSink sink, int x, int y, int width, int height, int radius) {
		if (!ready || broken || width <= 0 || height <= 0) {
			return false;
		}
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		if (r == 0) {
			blit(sink, x, y, width, height);
			return true;
		}
		// 上圆角：逐行收缩；中间整块；下圆角同样逐行收缩
		for (int i = 0; i < r; i++) {
			int inset = cornerInset(i, height, r);
			if (width - inset * 2 < 1) {
				break;
			}
			sink.blitRegion(LOCATION, x + inset, y + i, width - inset * 2, 1,
					x + inset, y + i, width - inset * 2, 1, texWidth, texHeight);
			int yy = y + height - 1 - i;
			int inset2 = cornerInset(height - 1 - i, height, r);
			if (width - inset2 * 2 >= 1) {
				sink.blitRegion(LOCATION, x + inset2, yy, width - inset2 * 2, 1,
						x + inset2, yy, width - inset2 * 2, 1, texWidth, texHeight);
			}
		}
		int middle = height - r * 2;
		if (middle > 0) {
			sink.blitRegion(LOCATION, x, y + r, width, middle, x, y + r, width, middle, texWidth, texHeight);
		}
		return true;
	}

	/** 圆角矩形第 {@code row} 行的左右内缩量（与 MapScreen 的圆角填充同一套算法） */
	private static int cornerInset(int row, int height, int radius) {
		int distance = Math.min(row, height - 1 - row);
		if (radius <= 0 || distance >= radius) {
			return 0;
		}
		double dy = radius - distance - 0.5;
		return radius - (int) Math.round(Math.sqrt(Math.max(0, radius * (double) radius - dy * dy)));
	}

	private static void blit(GuiSink sink, int screenX, int screenY, int w, int h) {
		sink.blitRegion(LOCATION, screenX, screenY, w, h, screenX, screenY, w, h, texWidth, texHeight);
	}

	// ===== 资源 =====

	private static void resize(Minecraft client, int width, int height) {
		release();
		texWidth = width;
		texHeight = height;
		int count = width * height;
		raw = new int[count];
		warped = new int[count];
		blurA = new int[count];
		blurB = new int[count];
		readBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());
		uploadBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());

		texture = GlStateManager._genTexture();
		GlStateManager._bindTexture(texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		GlStateManager._bindTexture(0);

		framebuffer = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
		GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, texture, 0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("毛玻璃 framebuffer 不完整: " + status);
		}

		textureHandle = new RawTexture(texture);
		client.getTextureManager().register(LOCATION, textureHandle);
	}

	private static void release() {
		if (framebuffer != -1) {
			GL30.glDeleteFramebuffers(framebuffer);
			framebuffer = -1;
		}
		if (texture != -1) {
			GlStateManager._deleteTexture(texture);
			texture = -1;
		}
		textureHandle = null;
	}

	// ===== 抓取 / 处理 / 上传 =====

	private static void readPixels(int fbWidth, int fbHeight) {
		int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		try {
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousDraw);
			// 用 GPU 直接缩小，省掉读整屏再 CPU 降采样的开销
			GL30.glBlitFramebuffer(0, 0, fbWidth, fbHeight, 0, 0, texWidth, texHeight,
					GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			readBuffer.clear();
			GL11.glReadPixels(0, 0, texWidth, texHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readBuffer);
		} finally {
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
		}
		readBuffer.clear();
		readBuffer.asIntBuffer().get(raw);
	}

	/** 翻转行序（GL 是下→上）、叠一层缓慢流动的液态扰动，然后模糊 */
	private static void process(double time, double guiScale) {
		int width = texWidth;
		int height = texHeight;
		// 扰动幅度按 GUI 像素算：1~3 像素，缓慢的正弦叠加，看起来很「液态」
		double amp = Math.max(1.0, Math.min(3.0, 2.0 + Math.sin(time * 0.35)));
		for (int y = 0; y < height; y++) {
			int sourceRow = (height - 1 - y) * width;
			double shift = amp * Math.sin(y * 0.055 + time * 0.9)
					+ (amp * 0.5) * Math.sin(y * 0.021 - time * 0.55);
			int offset = (int) Math.round(shift);
			int row = y * width;
			for (int x = 0; x < width; x++) {
				int sx = x + offset;
				if (sx < 0) {
					sx = 0;
				} else if (sx >= width) {
					sx = width - 1;
				}
				warped[row + x] = raw[sourceRow + sx];
			}
		}
		// 模糊半径：模糊纹理是 GUI 像素尺寸，半径按 GUI 缩放折算。
		// 小 GUI 缩放（1~2）时半径过大会让 4 遍盒式模糊的代价翻十几倍，这里封顶 6。
		int radius = (int) Math.round(10.0 / Math.max(1.0, guiScale));
		radius = Math.max(2, Math.min(6, radius));
		boxBlur(warped, blurA, blurB, width, height, radius);
		System.arraycopy(blurB, 0, raw, 0, width * height);
	}

	/** 横竖各两遍盒式模糊，视觉上接近高斯 */
	private static void boxBlur(int[] source, int[] horizontal, int[] vertical, int width, int height, int radius) {
		blurHorizontal(source, horizontal, width, height, radius);
		blurVertical(horizontal, vertical, width, height, radius);
		blurHorizontal(vertical, horizontal, width, height, radius);
		blurVertical(horizontal, vertical, width, height, radius);
	}

	private static void blurHorizontal(int[] source, int[] target, int width, int height, int radius) {
		int window = radius * 2 + 1;
		for (int y = 0; y < height; y++) {
			int base = y * width;
			long a = 0;
			long r = 0;
			long g = 0;
			long b = 0;
			for (int k = -radius; k <= radius; k++) {
				int pixel = source[base + clamp(k, 0, width - 1)];
				a += (pixel >>> 24) & 0xFF;
				r += (pixel >> 16) & 0xFF;
				g += (pixel >> 8) & 0xFF;
				b += pixel & 0xFF;
			}
			for (int x = 0; x < width; x++) {
				target[base + x] = ((int) (a / window) << 24) | ((int) (r / window) << 16)
						| ((int) (g / window) << 8) | (int) (b / window);
				int add = source[base + Math.min(width - 1, x + radius + 1)];
				int remove = source[base + Math.max(0, x - radius)];
				a += ((add >>> 24) & 0xFF) - ((remove >>> 24) & 0xFF);
				r += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
				g += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
				b += (add & 0xFF) - (remove & 0xFF);
			}
		}
	}

	private static void blurVertical(int[] source, int[] target, int width, int height, int radius) {
		int window = radius * 2 + 1;
		for (int x = 0; x < width; x++) {
			long a = 0;
			long r = 0;
			long g = 0;
			long b = 0;
			for (int k = -radius; k <= radius; k++) {
				int pixel = source[clamp(k, 0, height - 1) * width + x];
				a += (pixel >>> 24) & 0xFF;
				r += (pixel >> 16) & 0xFF;
				g += (pixel >> 8) & 0xFF;
				b += pixel & 0xFF;
			}
			for (int y = 0; y < height; y++) {
				target[y * width + x] = ((int) (a / window) << 24) | ((int) (r / window) << 16)
						| ((int) (g / window) << 8) | (int) (b / window);
				int add = source[Math.min(height - 1, y + radius + 1) * width + x];
				int remove = source[Math.max(0, y - radius) * width + x];
				a += ((add >>> 24) & 0xFF) - ((remove >>> 24) & 0xFF);
				r += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
				g += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
				b += (add & 0xFF) - (remove & 0xFF);
			}
		}
	}

	private static void upload() {
		uploadBuffer.clear();
		uploadBuffer.asIntBuffer().put(raw);
		uploadBuffer.limit(texWidth * texHeight * 4);
		GlStateManager._bindTexture(texture);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, texWidth, texHeight,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuffer);
		GlStateManager._bindTexture(0);
	}

	private static int clamp(int value, int min, int max) {
		return value < min ? min : Math.min(value, max);
	}

	/** 只用来持有我们自己生成的 GL 纹理 id，不参与资源包加载 */
	private static final class RawTexture extends AbstractTexture {
		RawTexture(int id) {
			this.id = id;
		}

		@Override
		public void load(ResourceManager manager) {
		}

		@Override
		public void close() {
			// 纹理由 GlassBackdrop 自己负责释放，避免重复删除
		}
	}
}
