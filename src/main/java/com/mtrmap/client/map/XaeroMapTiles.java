package com.mtrmap.client.map;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mtrmap.MtrMapCommon;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 底图：直接用 Xaero 世界地图已经渲染好的地图贴图。
 *
 * <p>Xaero 把世界切成多级的「分区」：第 L 级分区覆盖 {@code 512 * 2^L} 方块，内部是 8×8 张
 * 64×64 的贴图，每张贴图覆盖 {@code 64 * 2^L} 方块 —— 也就是 1 像素 = 2^L 方块，最粗到第 3 级
 * （1 像素 = 8 方块）。贴图是普通 RGBA8：RGB 是地图颜色、A 是光照，显示时要乘上
 * {@code max(A, 亮度)}（与 Xaero 自己的 shader 一致）。
 *
 * <p>贴图只存在显存里，所以取像素要用 {@code glGetTexImage}，也就必须在渲染线程跑；
 * 取回的像素按本模组自己的瓦片约定拼成 PNG，游戏内窗口与网页地图用的是同一批瓦片
 * （网页那份靠客户端上传）。
 *
 * <p>Xaero 是硬前置，但这里仍然全部走反射：Xaero 各加载器 / 各 MC 版本的 jar 不必随仓库分发。
 */
public final class XaeroMapTiles {

	/** Xaero 贴图边长 */
	private static final int TEX = 64;
	/** Xaero 最粗的一级（1 像素 = 8 方块） */
	private static final int MAX_LEVEL = 3;
	/** 一个分区里 8×8 张贴图 */
	private static final int REGION_TEX_SIDE = 8;
	/** 叶级分区覆盖的方块数（区块边长 512） */
	private static final int LEAF_REGION_BLOCKS = 512;
	/** 一次 {@link #tilePixels} 最多读回多少张贴图，免得一帧里把显存搬空 */
	private static final int READ_BUDGET = 512;
	/** 读回的贴图缓存上限（每张 64×64 int，16KB） */
	private static final int MAX_CACHE = 2048;
	/** 缓存存活时间：Xaero 的地图会随探索更新，到点整体重新读一遍 */
	private static final long CACHE_TTL_MS = 120_000L;
	/** 一次 preload 最多向 Xaero 请求多少片区域 */
	private static final int REQUEST_BUDGET = 96;

	/** level / 贴图坐标 → 64×64 颜色（无数据的贴图不入缓存） */
	private static final Map<String, int[]> TEXTURES =
			Collections.synchronizedMap(new LinkedHashMap<String, int[]>(MAX_CACHE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
					return size() > MAX_CACHE;
				}
			});
	/** 反射解析出来的方法，避免每帧反复扫方法表 */
	private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
	private static final Method NOT_FOUND;
	/** glGetTexImage 的落点，只在渲染线程用 */
	private static final ByteBuffer READ_BUFFER =
			ByteBuffer.allocateDirect(TEX * TEX * 4).order(ByteOrder.nativeOrder());

	private static volatile long cacheStamp = System.currentTimeMillis();
	/** 底图不能用的原因；正常时是 null */
	private static volatile String status;
	private static volatile boolean broken;
	private static Class<?> sessionClass;
	private static int budgetLeft;

	static {
		Method missing;
		try {
			missing = Object.class.getMethod("toString");
		} catch (NoSuchMethodException e) {
			missing = null;
		}
		NOT_FOUND = missing;
	}

	private XaeroMapTiles() {
	}

	/** 底图不能用的原因；一切正常时是 null */
	public static String status() {
		return status;
	}

	/** 断开连接 / 换存档时清空 */
	public static void clear() {
		TEXTURES.clear();
		cacheStamp = System.currentTimeMillis();
		status = null;
		broken = false;
	}

	/**
	 * 渲染线程调用：合成一张瓦片的 ARGB 像素；这块完全没有数据时返回 null。
	 *
	 * @param zoom 缩放级别（1 像素 = 2^(maxZoom-zoom) 方块）
	 */
	public static int[] tilePixels(int zoom, int tileX, int tileY) {
		WorldMapBridge.Settings wm = WorldMapBridge.settings();
		if (!wm.ok() || wm.tileSize <= 0) {
			return null;
		}
		Object processor = processor();
		if (processor == null) {
			return null;
		}
		expireIfNeeded();
		budgetLeft = READ_BUDGET;

		int cave = caveLayer(processor);
		float brightness = brightness(processor);
		int tileBpp = 1 << Math.max(0, wm.maxZoom - zoom);
		int originX = tileX * wm.tileSize * tileBpp;
		int originZ = tileY * wm.tileSize * tileBpp;
		int level = levelOf(zoom, wm.maxZoom);
		int[] out = new int[wm.tileSize * wm.tileSize];
		boolean any = false;
		// 需要的那一级最细，缺的地方用更粗的一级补上：缩得很远时也能看到地形
		for (int current = level; current <= MAX_LEVEL; current++) {
			any |= readLevel(processor, cave, brightness, current, originX, originZ,
					tileBpp, wm.tileSize, out, current > level);
		}
		return any ? out : null;
	}

	/** 把 ARGB 像素编码成 PNG（后台线程做的事，别在渲染线程调用） */
	public static byte[] encodePng(int[] pixels, int size) {
		try {
			BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, size, size, pixels, 0, size);
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
			javax.imageio.ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.warn("编码底图瓦片失败", t);
			return null;
		}
	}

	/**
	 * 渲染线程调用：让 Xaero 去准备这片区域的贴图。
	 *
	 * <p>照抄 Xaero 自己世界地图的做法：补出缺的叶级分区并请求加载，再请求它把当前所用那一级的
	 * 分区贴图拼出来。Xaero 按自己的节奏在后台加载，所以底图是逐步补齐的。
	 */
	public static void preload(int zoom, int tileX0, int tileZ0, int tileX1, int tileZ1) {
		WorldMapBridge.Settings wm = WorldMapBridge.settings();
		if (!wm.ok() || wm.tileSize <= 0) {
			return;
		}
		Object processor = processor();
		if (processor == null) {
			return;
		}
		int level = levelOf(zoom, wm.maxZoom);
		int tileBpp = 1 << Math.max(0, wm.maxZoom - zoom);
		long minBlockX = (long) tileX0 * wm.tileSize * tileBpp;
		long minBlockZ = (long) tileZ0 * wm.tileSize * tileBpp;
		long maxBlockX = (long) (tileX1 + 1) * wm.tileSize * tileBpp - 1;
		long maxBlockZ = (long) (tileZ1 + 1) * wm.tileSize * tileBpp - 1;

		int cave = caveLayer(processor);
		Object saveLoad = invoke(processor, "getMapSaveLoad");
		if (saveLoad == null) {
			return;
		}
		// 让 Xaero 优先准备我们正在看的这一级
		setMainTextureLevel(saveLoad, level);

		int requested = 0;
		// 叶级分区：Xaero 所有级别的贴图最终都从它长出来
		for (int regZ = (int) Math.floorDiv(minBlockZ, LEAF_REGION_BLOCKS);
				regZ <= (int) Math.floorDiv(maxBlockZ, LEAF_REGION_BLOCKS); regZ++) {
			for (int regX = (int) Math.floorDiv(minBlockX, LEAF_REGION_BLOCKS);
					regX <= (int) Math.floorDiv(maxBlockX, LEAF_REGION_BLOCKS); regX++) {
				if (leveledRegion(processor, cave, regX, regZ, 0) != null) {
					continue;
				}
				if (!bool(processor, "regionExists", cave, regX, regZ)) {
					continue;
				}
				Object region = invoke(processor, "getLeafMapRegion", cave, regX, regZ, true);
				if (region == null) {
					continue;
				}
				call(saveLoad, "requestLoad", region, "MtrMap", requested == 0);
				call(saveLoad, "setNextToLoadByViewing", region);
				if (++requested >= REQUEST_BUDGET) {
					return;
				}
			}
		}
		if (level <= 0) {
			return;
		}
		// 粗一级：请求 Xaero 从叶级贴图拼出这一级的分区贴图
		int shift = 9 + level;
		for (int regZ = (int) (minBlockZ >> shift); regZ <= (int) (maxBlockZ >> shift); regZ++) {
			for (int regX = (int) (minBlockX >> shift); regX <= (int) (maxBlockX >> shift); regX++) {
				Object region = leveledRegion(processor, cave, regX, regZ, level);
				if (region == null || bool(region, "isLoaded")) {
					continue;
				}
				call(region, "setReloadHasBeenRequested", true, "MtrMap");
				call(saveLoad, "requestBranchCache", region, "MtrMap", requested == 0);
				call(saveLoad, "setNextToLoadByViewing", region);
				if (++requested >= REQUEST_BUDGET) {
					return;
				}
			}
		}
	}

	// ===== 拼瓦片 =====

	/** 从某一级贴图里取像素铺进瓦片；{@code onlyEmpty} 表示只补还空着的地方 */
	private static boolean readLevel(Object processor, int cave, float brightness, int level,
			int originX, int originZ, int tileBpp, int size, int[] out, boolean onlyEmpty) {
		int texBlocks = TEX << level;
		int texBpp = 1 << level;
		int minTexX = Math.floorDiv(originX, texBlocks);
		int maxTexX = Math.floorDiv(originX + size * tileBpp - 1, texBlocks);
		int minTexZ = Math.floorDiv(originZ, texBlocks);
		int maxTexZ = Math.floorDiv(originZ + size * tileBpp - 1, texBlocks);
		boolean any = false;
		int minRegX = Math.floorDiv(minTexX, REGION_TEX_SIDE);
		int maxRegX = Math.floorDiv(maxTexX, REGION_TEX_SIDE);
		int minRegZ = Math.floorDiv(minTexZ, REGION_TEX_SIDE);
		int maxRegZ = Math.floorDiv(maxTexZ, REGION_TEX_SIDE);
		for (int regZ = minRegZ; regZ <= maxRegZ; regZ++) {
			for (int regX = minRegX; regX <= maxRegX; regX++) {
				Object leveled = leveledRegion(processor, cave, regX, regZ, level);
				if (leveled == null) {
					continue;
				}
				int fromZ = Math.max(0, minTexZ - regZ * REGION_TEX_SIDE);
				int toZ = Math.min(REGION_TEX_SIDE - 1, maxTexZ - regZ * REGION_TEX_SIDE);
				int fromX = Math.max(0, minTexX - regX * REGION_TEX_SIDE);
				int toX = Math.min(REGION_TEX_SIDE - 1, maxTexX - regX * REGION_TEX_SIDE);
				for (int inZ = fromZ; inZ <= toZ; inZ++) {
					for (int inX = fromX; inX <= toX; inX++) {
						int texX = regX * REGION_TEX_SIDE + inX;
						int texZ = regZ * REGION_TEX_SIDE + inZ;
						int[] pixels = texturePixels(leveled, brightness, level, texX, texZ, inX, inZ);
						if (pixels == null) {
							continue;
						}
						any = true;
						splat(out, size, pixels, texX * texBlocks, texZ * texBlocks, texBpp,
								originX, originZ, tileBpp, onlyEmpty);
					}
				}
			}
		}
		return any;
	}

	/** 把一张 Xaero 贴图铺到瓦片里 */
	private static void splat(int[] out, int size, int[] pixels, int blockX, int blockZ, int texBpp,
			int originX, int originZ, int tileBpp, boolean onlyEmpty) {
		int dstX = (blockX - originX) / tileBpp;
		int dstZ = (blockZ - originZ) / tileBpp;
		int span = TEX * texBpp / tileBpp;
		int scale = texBpp / tileBpp;
		if (scale >= 1) {
			// 贴图比瓦片细：一个贴图像素铺成 scale×scale 个瓦片像素
			for (int py = 0; py < TEX; py++) {
				int baseZ = dstZ + py * scale;
				for (int px = 0; px < TEX; px++) {
					int argb = pixels[py * TEX + px];
					if (argb == 0) {
						continue;
					}
					int baseX = dstX + px * scale;
					for (int y = 0; y < scale; y++) {
						int oy = baseZ + y;
						if (oy < 0 || oy >= size) {
							continue;
						}
						for (int x = 0; x < scale; x++) {
							int ox = baseX + x;
							if (ox < 0 || ox >= size) {
								continue;
							}
							int index = oy * size + ox;
							if (!onlyEmpty || out[index] == 0) {
								out[index] = argb;
							}
						}
					}
				}
			}
			return;
		}
		// 贴图比瓦片粗：一个瓦片像素取贴图的 group×group 个像素求平均
		int group = tileBpp / texBpp;
		for (int oy = Math.max(0, dstZ); oy < Math.min(size, dstZ + span); oy++) {
			int sy0 = (oy - dstZ) * group;
			for (int ox = Math.max(0, dstX); ox < Math.min(size, dstX + span); ox++) {
				int index = oy * size + ox;
				if (onlyEmpty && out[index] != 0) {
					continue;
				}
				int sx0 = (ox - dstX) * group;
				int a = 0;
				int r = 0;
				int g = 0;
				int b = 0;
				int count = 0;
				for (int y = 0; y < group; y++) {
					int row = (sy0 + y) * TEX + sx0;
					for (int x = 0; x < group; x++) {
						int argb = pixels[row + x];
						if (argb == 0) {
							continue;
						}
						a += (argb >>> 24) & 0xFF;
						r += (argb >> 16) & 0xFF;
						g += (argb >> 8) & 0xFF;
						b += argb & 0xFF;
						count++;
					}
				}
				if (count > 0) {
					out[index] = ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
				}
			}
		}
	}

	/** 取一张 Xaero 贴图的颜色；没数据（或这次的读取额度用完）返回 null */
	private static int[] texturePixels(Object leveled, float brightness, int level, int texX, int texZ,
			int inX, int inZ) {
		String key = level + "/" + texX + "_" + texZ;
		int[] cached = TEXTURES.get(key);
		if (cached != null) {
			return cached;
		}
		if (budgetLeft <= 0 || !bool(leveled, "hasTextures")) {
			return null;
		}
		Object texture = invoke(leveled, "getTexture", inX, inZ);
		if (texture == null || !bool(texture, "isUploaded")) {
			return null;
		}
		Object glTexture = invoke(texture, "getGlColorTexture");
		if (!(glTexture instanceof Number) || ((Number) glTexture).intValue() <= 0) {
			return null;
		}
		budgetLeft--;
		int[] pixels = readGl(((Number) glTexture).intValue(), bool(texture, "getTextureHasLight"), brightness);
		if (pixels != null) {
			TEXTURES.put(key, pixels);
		}
		return pixels;
	}

	/** 把一张 Xaero 贴图从显存读回 CPU：RGB 是颜色、A 是光照 */
	private static int[] readGl(int glTexture, boolean hasLight, float brightness) {
		try {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
			int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
			GlStateManager._bindTexture(glTexture);
			READ_BUFFER.clear();
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, READ_BUFFER);
			GlStateManager._bindTexture(previous);
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.warn("读取 Xaero 地图贴图失败", t);
			return null;
		}
		int[] pixels = new int[TEX * TEX];
		boolean any = false;
		for (int i = 0; i < pixels.length; i++) {
			int r = READ_BUFFER.get(i * 4) & 0xFF;
			int g = READ_BUFFER.get(i * 4 + 1) & 0xFF;
			int b = READ_BUFFER.get(i * 4 + 2) & 0xFF;
			int a = READ_BUFFER.get(i * 4 + 3) & 0xFF;
			// Xaero 用 (0,0,0,*) 表示没有数据
			if (r == 0 && g == 0 && b == 0) {
				continue;
			}
			float light = hasLight ? Math.max(a / 255f, brightness) : 1f;
			pixels[i] = 0xFF000000 | (channel(r * light) << 16) | (channel(g * light) << 8) | channel(b * light);
			any = true;
		}
		return any ? pixels : null;
	}

	private static int channel(float value) {
		int v = Math.round(value);
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	/** 我们的 zoom → Xaero 的贴图级别（1 像素 = 2^level 方块） */
	private static int levelOf(int zoom, int maxZoom) {
		return Math.max(0, Math.min(MAX_LEVEL, maxZoom - zoom));
	}

	// ===== Xaero 接入（全部反射） =====

	/** Xaero 当前的 MapProcessor（没进世界 / 没装都是 null） */
	private static Object processor() {
		if (broken) {
			return null;
		}
		try {
			if (sessionClass == null) {
				sessionClass = Class.forName("xaero.map.WorldMapSession");
			}
			Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
			if (session == null) {
				status = "Xaero 尚未进入世界";
				return null;
			}
			Object processor = invoke(session, "getMapProcessor");
			if (processor == null) {
				status = "Xaero 地图尚未初始化";
				return null;
			}
			status = null;
			return processor;
		} catch (ClassNotFoundException e) {
			broken = true;
			status = "未安装 Xaero 世界地图";
			MtrMapCommon.LOGGER.warn("游戏内地图：没有找到 Xaero 世界地图（xaero.map.WorldMapSession）");
			return null;
		} catch (Throwable t) {
			broken = true;
			status = "接入 Xaero 世界地图失败：" + t.getClass().getSimpleName();
			MtrMapCommon.LOGGER.warn("游戏内地图：接入 Xaero 世界地图失败", t);
			return null;
		}
	}

	private static int caveLayer(Object processor) {
		Object layer = invoke(processor, "getCurrentCaveLayer");
		return layer instanceof Number ? ((Number) layer).intValue() : 0;
	}

	private static float brightness(Object processor) {
		Object value = invoke(processor, "getBrightness");
		return value instanceof Number ? ((Number) value).floatValue() : 1f;
	}

	private static Object leveledRegion(Object processor, int cave, int regX, int regZ, int level) {
		return invoke(processor, "getLeveledRegion", cave, regX, regZ, level);
	}

	/** MapSaveLoad.mainTextureLevel：让 Xaero 优先准备这一级的贴图 */
	private static void setMainTextureLevel(Object saveLoad, int level) {
		try {
			Field field = saveLoad.getClass().getField("mainTextureLevel");
			if (field.getInt(saveLoad) != level) {
				field.setInt(saveLoad, level);
			}
		} catch (Throwable t) {
			// 字段改名就跳过：这只是提速用的
		}
	}

	// ===== 反射小工具（按参数个数 + 可赋值性匹配，容忍 Xaero 换签名） =====

	private static Object invoke(Object target, String name, Object... args) {
		Method method = resolve(target.getClass(), name, args);
		if (method == null) {
			return null;
		}
		try {
			return method.invoke(target, args);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void call(Object target, String name, Object... args) {
		Method method = resolve(target.getClass(), name, args);
		if (method == null) {
			return;
		}
		try {
			method.invoke(target, args);
		} catch (Throwable ignored) {
			// 调用失败按「没请求过」处理，下一帧会再试
		}
	}

	private static boolean bool(Object target, String name, Object... args) {
		Object value = invoke(target, name, args);
		return value instanceof Boolean && (Boolean) value;
	}

	private static Method resolve(Class<?> type, String name, Object[] args) {
		String key = type.getName() + "#" + name + "/" + args.length;
		Method cached = METHODS.get(key);
		if (cached != null) {
			if (cached == NOT_FOUND) {
				return null;
			}
			if (matches(cached, args)) {
				return cached;
			}
		}
		Method found = null;
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name) && matches(method, args)) {
				found = method;
				break;
			}
		}
		METHODS.put(key, found == null ? NOT_FOUND : found);
		return found;
	}

	private static boolean matches(Method method, Object[] args) {
		Class<?>[] params = method.getParameterTypes();
		if (params.length != args.length) {
			return false;
		}
		for (int i = 0; i < params.length; i++) {
			if (args[i] == null) {
				if (params[i].isPrimitive()) {
					return false;
				}
			} else if (!wrap(params[i]).isInstance(args[i])) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) {
			return type;
		}
		if (type == int.class) {
			return Integer.class;
		}
		if (type == long.class) {
			return Long.class;
		}
		if (type == boolean.class) {
			return Boolean.class;
		}
		if (type == float.class) {
			return Float.class;
		}
		if (type == double.class) {
			return Double.class;
		}
		if (type == short.class) {
			return Short.class;
		}
		if (type == byte.class) {
			return Byte.class;
		}
		return type == char.class ? Character.class : Void.class;
	}

	/** 缓存到期就整体清一次，让新探索到的地形能出现 */
	private static void expireIfNeeded() {
		long now = System.currentTimeMillis();
		if (now - cacheStamp > CACHE_TTL_MS) {
			TEXTURES.clear();
			cacheStamp = now;
		}
	}
}
