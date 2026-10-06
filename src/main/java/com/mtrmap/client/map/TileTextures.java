package com.mtrmap.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mtrmap.MtrMapCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 世界地图瓦片纹理缓存。
 *
 * <p>瓦片是 512×512 的 PNG：像素来自本机 Xaero 的地图贴图（{@link XaeroMapTiles}），
 * 读显存只能在渲染线程做，所以 {@link #get} 这一侧先把像素取出来，编码 PNG、上传给服务端
 * （网页地图用）、以及解码注册纹理都交给后台线程；纹理注册（必须回渲染线程）通过
 * {@link Minecraft#execute} 投递。缓存按 LRU 淘汰，超出上限的纹理调用
 * {@code TextureManager.release} 释放显存。
 *
 * <p>尚未就绪的瓦片返回 null，由调用方跳过；下一帧会再问一次。
 */
public final class TileTextures {

	/** 同时保留的瓦片纹理上限（每张 512×512，约 1MB 显存） */
	private static final int MAX_TEXTURES = 160;
	/** 与服务端的并发连接数上限 */
	private static final int WORKERS = 3;
	/** 一次渲染内最多合成多少张新瓦片：合成要在渲染线程读显存，不能一次读太多 */
	private static final int MAX_REQUESTS_PER_FRAME = 2;
	/** 取不到数据后的重试间隔 */
	private static final long FAILED_RETRY_MS = 10_000L;
	/** 瓦片存活时间：Xaero 的地图会随探索更新，到点重新合成一次 */
	private static final long REFRESH_MS = 120_000L;

	private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
	/** 访问顺序（LRU）：末尾是最新的 */
	private static final LinkedHashMap<String, Boolean> ACCESS_ORDER = new LinkedHashMap<>();
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
			WORKERS, WORKERS, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
			runnable -> {
				Thread thread = new Thread(runnable, "MTRMap-Tile");
				thread.setDaemon(true);
				return thread;
			},
			new ThreadPoolExecutor.DiscardPolicy());

	private static int requestsThisFrame;

	private TileTextures() {
	}

	/** 每帧开始时重置请求配额 */
	public static void beginFrame() {
		requestsThisFrame = 0;
	}

	private static final class Entry {
		volatile ResourceLocation location;
		volatile boolean failed;
		volatile long failedAt;
		volatile long createdAt;
	}

	/**
	 * 取一张世界地图瓦片的纹理；未就绪返回 null。
	 *
	 * @param zoom  缩放级别（越大越细，1 像素 = 2^(maxZoom-zoom) 方块）
	 * @param tileX 瓦片 x 索引（世界 x 整除瓦片覆盖的方块数）
	 * @param tileY 瓦片 y 索引（世界 z 同理）
	 */
	public static ResourceLocation get(int zoom, int tileX, int tileY) {
		String key = zoom + "/" + tileX + "_" + tileY;
		Entry entry = ENTRIES.get(key);
		long now = System.currentTimeMillis();
		if (entry == null) {
			entry = new Entry();
			ENTRIES.put(key, entry);
			request(key, zoom, tileX, tileY, entry);
		} else if (entry.location != null && now - entry.createdAt > REFRESH_MS) {
			// 到点了：放掉旧的重新合成，好让新探索到的地形、刚补齐的粗贴图出现
			Minecraft.getInstance().getTextureManager().release(entry.location);
			entry.location = null;
			entry.createdAt = now;
			request(key, zoom, tileX, tileY, entry);
		} else if (entry.failed && now - entry.failedAt > FAILED_RETRY_MS) {
			entry.failed = false;
			request(key, zoom, tileX, tileY, entry);
		}
		ResourceLocation location = entry.location;
		if (location != null) {
			markUsed(key);
		}
		return location;
	}

	private static void request(String key, int zoom, int tileX, int tileY, Entry entry) {
		if (requestsThisFrame >= MAX_REQUESTS_PER_FRAME || IN_FLIGHT.get() >= WORKERS * 4) {
			// 这帧排不上了，留着下一帧再来（failedAt = 0 让它立刻可重试）
			entry.failed = true;
			entry.failedAt = 0L;
			return;
		}
		requestsThisFrame++;
		WorldMapBridge.Settings settings = WorldMapBridge.settings();
		// 底图直接来自本机 Xaero 的地图贴图，而读显存只能在渲染线程做，所以先在这里取像素
		int[] pixels = settings.ok() ? XaeroMapTiles.tilePixels(zoom, tileX, tileY) : null;
		int size = settings.tileSize;
		String path = WorldMapBridge.tilePath(zoom, tileX, tileY);
		IN_FLIGHT.incrementAndGet();
		POOL.execute(() -> {
			try {
				byte[] bytes = pixels == null ? null : XaeroMapTiles.encodePng(pixels, size);
				if (bytes != null) {
					// 顺手传给服务端：网页地图用的就是这批瓦片
					MapDataClient.postBytes(path, bytes);
				} else {
					// 本机这块还没数据，先看看服务端有没有别人传过的
					bytes = MapDataClient.getBytes(path);
				}
				if (bytes == null || bytes.length == 0) {
					entry.failed = true;
					entry.failedAt = System.currentTimeMillis();
					return;
				}
				NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
				// 纹理必须在渲染线程注册
				Minecraft.getInstance().execute(() -> register(key, image));
			} catch (Throwable t) {
				entry.failed = true;
				entry.failedAt = System.currentTimeMillis();
			} finally {
				IN_FLIGHT.decrementAndGet();
			}
		});
	}

	private static void register(String key, NativeImage image) {
		try {
			Entry entry = ENTRIES.get(key);
			if (entry == null || entry.location != null) {
				image.close();
				return;
			}
			// 1.21 起 ResourceLocation 的公开构造函数被隐藏，改用 fromNamespaceAndPath
			//? if >=1.21.1 {
			/*ResourceLocation location = ResourceLocation.fromNamespaceAndPath("mtrmap", "worldmap/" + key);
			*///?} else {
			ResourceLocation location = new ResourceLocation("mtrmap", "worldmap/" + key);
			//?}
			Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
			entry.location = location;
			entry.failed = false;
			entry.createdAt = System.currentTimeMillis();
			markUsed(key);
			evictIfNeeded();
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.debug("注册世界地图瓦片纹理失败: {}", key, t);
			image.close();
		}
	}

	/** 记录访问顺序（LRU），必须在客户端线程调用 */
	private static void markUsed(String key) {
		synchronized (ACCESS_ORDER) {
			ACCESS_ORDER.remove(key);
			ACCESS_ORDER.put(key, Boolean.TRUE);
		}
	}

	private static void evictIfNeeded() {
		synchronized (ACCESS_ORDER) {
			Iterator<Map.Entry<String, Boolean>> iterator = ACCESS_ORDER.entrySet().iterator();
			while (ACCESS_ORDER.size() > MAX_TEXTURES && iterator.hasNext()) {
				String oldest = iterator.next().getKey();
				iterator.remove();
				Entry entry = ENTRIES.remove(oldest);
				if (entry != null && entry.location != null) {
					Minecraft.getInstance().getTextureManager().release(entry.location);
					entry.location = null;
				}
			}
		}
	}

	/** 断开连接 / 换存档时清空 */
	public static void clear() {
		synchronized (ACCESS_ORDER) {
			for (Entry entry : ENTRIES.values()) {
				if (entry.location != null) {
					Minecraft.getInstance().getTextureManager().release(entry.location);
				}
			}
			ENTRIES.clear();
			ACCESS_ORDER.clear();
		}
	}
}
