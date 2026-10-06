package com.mtrmap.server;

import com.mtrmap.MtrMapCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
// 1.20.5 起 ChunkStatus 从 world.level.chunk 移到了 world.level.chunk.status
//? if >=1.21.1 {
/*import net.minecraft.world.level.chunk.status.ChunkStatus;
*///?} else {
import net.minecraft.world.level.chunk.ChunkStatus;
//?}
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研世界地图（底图）：不依赖任何外部地图模组，直接把服务器**已加载**的区块采样成瓦片。
 *
 * <p>工作方式：
 * <ol>
 *   <li>服务器每 tick 在玩家周围扫一遍，把还没采过的区块采样成 16×16 的地图色像素（{@link #CHUNKS}）；</li>
 *   <li>网页 / 游戏内地图窗口请求某张瓦片时，把落在该瓦片范围内的区块像素按缩放级别合成 512×512 PNG；</li>
 *   <li>因此地图只覆盖"服务器当前加载过的区域"（也就是玩家活动范围），不会为了画地图强制加载区块。</li>
 * </ol>
 *
 * <p>缩放约定（与网页、游戏内地图窗口一致）：
 * {@code 1 像素 = 2^(MAX_ZOOM - zoom) 方块}，瓦片边长 {@link #TILE_SIZE} 像素，
 * 所以一张瓦片覆盖 {@code TILE_SIZE * 2^(MAX_ZOOM - zoom)} 方块，坐标原点在世界 (0,0)，
 * x 向东、z 向南，瓦片索引都是整除取下取整。
 *
 * <p>采样只读取 {@code ChunkStatus.FULL} 且已经加载的区块，绝不触发世界生成。
 */
public final class WorldMapTiles {

	/** 瓦片边长（像素） */
	public static final int TILE_SIZE = 512;
	/** 最细一级：1 像素 = 1 方块 */
	public static final int MAX_ZOOM = 4;
	/** 最粗一级：1 像素 = 16 方块 */
	public static final int MIN_ZOOM = 0;

	private static final int CHUNK_BLOCKS = 16;
	/** 采样半径（区块）：玩家周围这么多区块内的区块会被采样 */
	private static final int SAMPLE_RADIUS = 12;
	/** 每多少 tick 扫一次 */
	private static final int SWEEP_INTERVAL = 10;
	/** 每次扫描最多新采样多少区块，避免一帧内做太多方块查询 */
	private static final int SWEEP_BUDGET = 96;
	/** 合成的瓦片图缓存数量（每张 512×512 int，约 1MB） */
	private static final int IMAGE_CACHE = 16;

	/** 区块像素：16×16 的地图色（ARGB） */
	private static final Map<Long, int[]> CHUNKS = new ConcurrentHashMap<>();
	/** 合成好的瓦片：key = "zoom/tx_ty" */
	private static final Map<String, Cached> TILES =
			Collections.synchronizedMap(new LinkedHashMap<String, Cached>(IMAGE_CACHE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
					return size() > IMAGE_CACHE;
				}
			});

	/** 只要有新采样的区块就 +1，用来让合成结果过期 */
	private static volatile int revision;
	private static volatile String worldName = "overworld";
	private static int tickCounter;
	private static BlockPos.MutableBlockPos cursor = BlockPos.ZERO.mutable();

	private WorldMapTiles() {
	}

	// ===== 采样 =====

	/** 服务器每 tick 调用：在玩家周围补采区块 */
	public static void tick(MinecraftServer server) {
		if (server == null || server.getPlayerList() == null) {
			return;
		}
		if (tickCounter++ % SWEEP_INTERVAL != 0) {
			return;
		}
		ServerLevel level = server.overworld();
		if (level == null) {
			return;
		}
		worldName = level.dimension().location().getPath();
		int budget = SWEEP_BUDGET;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (budget <= 0) {
				break;
			}
			int cx = player.blockPosition().getX() >> 4;
			int cz = player.blockPosition().getZ() >> 4;
			for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS && budget > 0; dz++) {
				for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS && budget > 0; dx++) {
					if (sampleChunk(level, cx + dx, cz + dz)) {
						budget--;
					}
				}
			}
		}
	}

	/** 采样一个区块；未加载或已采过返回 false */
	private static boolean sampleChunk(ServerLevel level, int cx, int cz) {
		long key = pack(cx, cz);
		if (CHUNKS.containsKey(key)) {
			return false;
		}
		ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null || !(chunk instanceof LevelChunk)) {
			return false;
		}
		int[] pixels = new int[CHUNK_BLOCKS * CHUNK_BLOCKS];
		int[] heights = new int[CHUNK_BLOCKS * CHUNK_BLOCKS];
		int baseX = cx << 4;
		int baseZ = cz << 4;
		int minY = level.getMinBuildHeight();

		// 先取高度与颜色，再统一加浮雕阴影（要和北侧一列比较高度，所以分两趟）
		for (int z = 0; z < CHUNK_BLOCKS; z++) {
			for (int x = 0; x < CHUNK_BLOCKS; x++) {
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int y = top - 1;
				heights[z * CHUNK_BLOCKS + x] = y;
				if (y < minY) {
					pixels[z * CHUNK_BLOCKS + x] = VOID_COLOR;
					continue;
				}
				BlockState state = chunk.getBlockState(cursor.set(baseX + x, y, baseZ + z));
				pixels[z * CHUNK_BLOCKS + x] = mapColor(state, level, cursor);
			}
		}
		for (int z = 0; z < CHUNK_BLOCKS; z++) {
			for (int x = 0; x < CHUNK_BLOCKS; x++) {
				int index = z * CHUNK_BLOCKS + x;
				int y = heights[index];
				if (y < minY) {
					continue;
				}
				float shade = 1f;
				if (z > 0) {
					// 相邻列的高度差 → 明暗，做出地形起伏感（和原版地图一个思路）
					shade = clampShade(1f + (y - heights[(z - 1) * CHUNK_BLOCKS + x]) * 0.045f);
				}
				pixels[index] = shade(pixels[index], shade);
			}
		}
		CHUNKS.put(key, pixels);
		revision++;
		return true;
	}

	/** 无数据（未加载 / 虚空）时的底色 */
	private static final int VOID_COLOR = 0xFF16161E;

	// ===== 取瓦片 =====

	/** 瓦片 PNG；该范围完全没有数据时返回 null（调用方按 404 处理） */
	public static byte[] tilePng(int zoom, int tx, int ty) {
		int[] data = tileData(zoom, tx, ty);
		if (data == null) {
			return null;
		}
		try {
			BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, TILE_SIZE, TILE_SIZE, data, 0, TILE_SIZE);
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.warn("合成世界地图瓦片失败 ({}/{}_{})", zoom, tx, ty, t);
			return null;
		}
	}

	/** 合成后的瓦片像素；无数据返回 null */
	private static int[] tileData(int zoom, int tx, int ty) {
		if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
			return null;
		}
		String key = zoom + "/" + tx + "_" + ty;
		int rev = revision;
		synchronized (TILES) {
			Cached cached = TILES.get(key);
			if (cached != null && cached.revision == rev) {
				return cached.data;
			}
		}
		int[] data = compose(zoom, tx, ty);
		if (data == null) {
			return null;
		}
		TILES.put(key, new Cached(data, rev));
		return data;
	}

	/**
	 * 把落在瓦片范围内的区块像素合成成 512×512。
	 *
	 * <p>每像素覆盖 {@code bpp = 2^(MAX_ZOOM - zoom)} 个方块，所以一个区块（16 方块）
	 * 在瓦片里占 {@code 16/bpp} 见方个像素，按 bpp×bpp 取平均。
	 */
	private static int[] compose(int zoom, int tx, int ty) {
		int bpp = 1 << (MAX_ZOOM - zoom);
		int tileBlocks = TILE_SIZE * bpp;
		int minCX = Math.floorDiv(tx * tileBlocks, CHUNK_BLOCKS);
		int minCZ = Math.floorDiv(ty * tileBlocks, CHUNK_BLOCKS);
		int chunksPerTile = tileBlocks / CHUNK_BLOCKS;
		int maxCX = minCX + chunksPerTile - 1;
		int maxCZ = minCZ + chunksPerTile - 1;

		int[] data = new int[TILE_SIZE * TILE_SIZE];
		int pixelsPerChunk = CHUNK_BLOCKS / bpp;
		boolean any = false;
		for (Map.Entry<Long, int[]> entry : CHUNKS.entrySet()) {
			int cx = unpackX(entry.getKey());
			int cz = unpackZ(entry.getKey());
			if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) {
				continue;
			}
			any = true;
			int[] pixels = entry.getValue();
			int originPx = (cx - minCX) * pixelsPerChunk;
			int originPy = (cz - minCZ) * pixelsPerChunk;
			for (int py = 0; py < pixelsPerChunk; py++) {
				for (int px = 0; px < pixelsPerChunk; px++) {
					int argb = average(pixels, px * bpp, py * bpp, bpp);
					data[(originPy + py) * TILE_SIZE + originPx + px] = argb;
				}
			}
		}
		return any ? data : null;
	}

	/** 取区块里 (x0,y0) 起 bpp×bpp 块的平均色（忽略透明） */
	private static int average(int[] pixels, int x0, int y0, int bpp) {
		if (bpp == 1) {
			return pixels[y0 * CHUNK_BLOCKS + x0];
		}
		long a = 0;
		long r = 0;
		long g = 0;
		long b = 0;
		int count = 0;
		for (int y = 0; y < bpp; y++) {
			for (int x = 0; x < bpp; x++) {
				int argb = pixels[(y0 + y) * CHUNK_BLOCKS + x0 + x];
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha == 0) {
					continue;
				}
				a += alpha;
				r += (argb >> 16) & 0xFF;
				g += (argb >> 8) & 0xFF;
				b += argb & 0xFF;
				count++;
			}
		}
		if (count == 0) {
			return 0;
		}
		return ((int) (a / count) << 24) | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
	}

	// ===== 颜色 =====

	/**
	 * 方块的地图颜色。
	 *
	 * <p>这类颜色对象 1.20.1 叫 MaterialColor、1.21 起改名 MapColor，字段都是 {@code col}；
	 * 这里用 {@code var} 推断，避开跨版本的类名差异。
	 */
	private static int mapColor(BlockState state, ServerLevel level, BlockPos pos) {
		try {
			var color = state.getMapColor(level, pos);
			return color == null ? VOID_COLOR : 0xFF000000 | color.col;
		} catch (Throwable t) {
			return VOID_COLOR;
		}
	}

	private static int shade(int argb, float factor) {
		int a = (argb >>> 24) & 0xFF;
		if (a == 0) {
			return argb;
		}
		int r = clampChannel((int) (((argb >> 16) & 0xFF) * factor));
		int g = clampChannel((int) (((argb >> 8) & 0xFF) * factor));
		int b = clampChannel((int) ((argb & 0xFF) * factor));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int clampChannel(int value) {
		return value < 0 ? 0 : (value > 255 ? 255 : value);
	}

	private static float clampShade(float value) {
		return value < 0.62f ? 0.62f : (value > 1.24f ? 1.24f : value);
	}

	// ===== 状态 =====

	/** 服务器关闭 / 重载时清空 */
	public static void clear() {
		CHUNKS.clear();
		TILES.clear();
		revision++;
		tickCounter = 0;
	}

	/** 已采样的区块数（给 /api/worldmap/settings 显示用） */
	public static int sampledChunks() {
		return CHUNKS.size();
	}

	public static String worldName() {
		return worldName;
	}

	// ===== key 打包 =====

	private static long pack(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	private static int unpackX(long key) {
		return (int) (key >> 32);
	}

	private static int unpackZ(long key) {
		return (int) key;
	}

	/** 合成结果的缓存项 */
	private static final class Cached {
		final int[] data;
		final int revision;

		Cached(int[] data, int revision) {
			this.data = data;
			this.revision = revision;
		}
	}
}
