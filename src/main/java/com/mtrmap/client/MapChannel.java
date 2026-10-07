package com.mtrmap.client;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏内地图窗口 / 导航的取数通道。
 *
 * <p>以前这些东西是直接访问本机 http://服务器:1145 的 JSON 端点，端口没同步到、
 * 防火墙拦了或者服务没起来，F6 窗口就是一片空白。现在改走模组网络包：
 * 客户端发一个「取数请求」（path + 可选的 POST 体），服务端在同一条通道上把
 * 结果分块发回来，由本类拼回整段 JSON 文本返回。
 *
 * <p>请求/响应用 {@code requestId} 配对；服务端回包可能很大（线网数据几百 KB），
 * 所以按 {@link MtrMapCommon#MAP_CHUNK_SIZE} 切片，全部到齐才算响应完成。
 *
 * <p>{@link #request(String, String)} 是阻塞式的，语义与原来的 HTTP 调用一致，
 * 因此调用方一律是后台线程；服务端回包在客户端线程/网络线程到达，不会与等待方互相死锁。
 */
public final class MapChannel {

	/** 单次请求超时（毫秒）：线网大时服务端拼 JSON 要花时间，给宽一点 */
	private static final long TIMEOUT_MS = 8000L;

	private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
	/** requestId -> 等待中的请求 */
	private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
	/** requestId -> 正在拼装的分块 */
	private static final Map<Integer, Assembler> ASSEMBLY = new ConcurrentHashMap<>();
	/** 最近一次失败原因（null 表示还没失败过） */
	private static volatile String lastError;

	private MapChannel() {
	}

	/** 最近一次失败原因；用于地图窗口的连接提示 */
	public static String lastError() {
		return lastError;
	}

	/**
	 * 阻塞式取数：返回响应体 JSON 文本；未连接、超时或出错时返回 null。
	 *
	 * @param path 形如 {@code /api/data}，可带查询串
	 * @param body POST 体（JSON 文本），GET 请求传 null
	 */
	public static String request(String path, String body) {
		int requestId = NEXT_ID.getAndIncrement();
		Pending pending = new Pending();
		PENDING.put(requestId, pending);
		try {
			send(requestId, path, body == null ? "" : body);
		} catch (Throwable t) {
			PENDING.remove(requestId);
			lastError = describe(t);
			return null;
		}
		try {
			if (!pending.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				lastError = "等待服务端响应超时";
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			PENDING.remove(requestId);
			ASSEMBLY.remove(requestId);
		}
		return pending.body;
	}

	/**
	 * 网络包只能在客户端线程发送，而调用方都在后台线程，
	 * 所以这里转到客户端线程去发；已经在客户端线程时直接发。
	 */
	private static void send(int requestId, String path, String body) {
		Minecraft client = Minecraft.getInstance();
		if (client != null && !client.isSameThread()) {
			client.execute(() -> {
				try {
					Platform.get().sendMapRequest(requestId, path, body);
				} catch (Throwable t) {
					fail(requestId, t);
				}
			});
		} else {
			Platform.get().sendMapRequest(requestId, path, body);
		}
	}

	/** 收到服务端回包的一个分块；由平台模块的接收器调用。 */
	public static void onChunk(int requestId, int index, int total, byte[] chunk) {
		Pending pending = PENDING.get(requestId);
		if (pending == null || chunk == null) {
			// 超时后姗姗来迟的分块，直接丢掉
			return;
		}
		Assembler assembler = ASSEMBLY.computeIfAbsent(requestId, key -> new Assembler(total));
		assembler.put(index, chunk);
		if (!assembler.complete()) {
			return;
		}
		ASSEMBLY.remove(requestId);
		pending.body = assembler.text();
		lastError = null;
		pending.latch.countDown();
	}

	/** 断开连接：放弃所有在途请求，避免调用方一直等到超时 */
	public static void reset() {
		lastError = null;
		for (Pending pending : PENDING.values()) {
			pending.latch.countDown();
		}
		PENDING.clear();
		ASSEMBLY.clear();
	}

	private static void fail(int requestId, Throwable t) {
		lastError = describe(t);
		Pending pending = PENDING.remove(requestId);
		if (pending != null) {
			pending.latch.countDown();
		}
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isEmpty() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
	}

	/** 一次等待中的请求 */
	private static final class Pending {
		private final CountDownLatch latch = new CountDownLatch(1);
		private volatile String body;
	}

	/** 按序号收集分块，集齐后拼成整段文本 */
	private static final class Assembler {

		private final byte[][] parts;
		private final boolean[] filled;
		private int received;

		private Assembler(int total) {
			int size = Math.max(1, total);
			this.parts = new byte[size][];
			this.filled = new boolean[size];
		}

		private synchronized void put(int index, byte[] chunk) {
			if (index < 0 || index >= parts.length || filled[index]) {
				return;
			}
			parts[index] = chunk;
			filled[index] = true;
			received++;
		}

		private synchronized boolean complete() {
			return received == parts.length;
		}

		private synchronized String text() {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (byte[] part : parts) {
				if (part != null) {
					out.write(part, 0, part.length);
				}
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
