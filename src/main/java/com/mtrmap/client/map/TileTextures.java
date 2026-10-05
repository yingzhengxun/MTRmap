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
 * squaremap 瓦片纹理缓存。
 *
 * <p>瓦片是 512×512 的 PNG，从 squaremap 的 HTTP 服务拉取；解码与网络都在后台线程完成，
 * 纹理注册（必须回渲染线程）通过 {@link Minecraft#execute} 投递。缓存按 LRU 淘汰，
 * 超出上限的纹理调用 {@code TextureManager.release} 释放显存。
 *
 * <p>尚未就绪的瓦片返回 null，由调用方跳过；下一帧会再问一次。
 */
public final class TileTextures {

	/** 同时保留的瓦片纹理上限（每张 512×512，约 1MB 显存） */
	private static final int MAX_TEXTURES = 160;
	/** 与 squaremap 的并发连接数上限 */
	private static final int WORKERS = 3;
	/** 一次渲染内最多发起多少张新瓦片请求，避免缩放瞬间打出上百个请求 */
	private static final int MAX_REQUESTS_PER_FRAME = 6;

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

	private static String currentBaseUrl;
	private static String currentWorld;
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
	}

	/**
	 * 取一张瓦片的纹理；未就绪返回 null。
	 *
	 * @param baseUrl squaremap 的 Web 根地址
	 * @param world   世界目录名
	 */
	public static ResourceLocation get(String baseUrl, String world, int zoom, int tileX, int tileY) {
		// squaremap 换地址/换世界时整体作废
		if (!baseUrl.equals(currentBaseUrl) || !world.equals(currentWorld)) {
			reset(baseUrl, world);
		}
		String key = zoom + "/" + tileX + "_" + tileY;
		Entry entry = ENTRIES.get(key);
		if (entry == null) {
			entry = new Entry();
			ENTRIES.put(key, entry);
			request(baseUrl, world, key, zoom, tileX, tileY, entry);
		} else if (entry.failed && System.currentTimeMillis() - entry.failedAt > 10_000L) {
			// 暂时失败（服务器还没起来等）允许重试
			entry.failed = false;
			request(baseUrl, world, key, zoom, tileX, tileY, entry);
		}
		ResourceLocation location = entry.location;
		if (location != null) {
			markUsed(key);
		}
		return location;
	}

	private static void request(String baseUrl, String world, String key, int zoom, int tileX, int tileY, Entry entry) {
		if (requestsThisFrame >= MAX_REQUESTS_PER_FRAME || IN_FLIGHT.get() >= WORKERS * 4) {
			return;
		}
		requestsThisFrame++;
		IN_FLIGHT.incrementAndGet();
		String url = SquaremapBridge.tileUrl(newUrl(baseUrl, world), zoom, tileX, tileY);
		POOL.execute(() -> {
			try {
				byte[] bytes = SquaremapBridge.getBytes(url);
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
			/*ResourceLocation location = ResourceLocation.fromNamespaceAndPath("mtrmap", "squaremap/" + key);
			*///?} else {
			ResourceLocation location = new ResourceLocation("mtrmap", "squaremap/" + key);
			//?}
			Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
			entry.location = location;
			markUsed(key);
			evictIfNeeded();
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.debug("注册瓦片纹理失败: {}", key, t);
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

	private static void reset(String baseUrl, String world) {
		currentBaseUrl = baseUrl;
		currentWorld = world;
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

	/** 断开连接 / 换存档时清空 */
	public static void clear() {
		currentBaseUrl = null;
		currentWorld = null;
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

	private static SquaremapBridge.Result newUrl(String baseUrl, String world) {
		SquaremapBridge.Result result = new SquaremapBridge.Result();
		result.baseUrl = baseUrl;
		result.world = world;
		return result;
	}
}
