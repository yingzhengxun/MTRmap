package com.mtrmap.server;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mtrmap.MtrMapCommon;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家头像处理器。
 *
 * 优先使用客户端经网络通道推送的头像（数据源为客户端已加载的皮肤纹理，
 * 与 Chat Heads 模组一致）；缓存未命中时回退为服务端自行下载皮肤并裁剪。
 * 裁剪时取头部区域(含帽子层)并缓存为 PNG。
 */
public class AvatarHandler {

	/** 连接超时（毫秒） */
	private static final int CONNECT_TIMEOUT_MS = 5000;
	/** 读取超时（毫秒） */
	private static final int READ_TIMEOUT_MS = 10000;

	private static final int AVATAR_SIZE = 64;
	private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
	private static final byte[] EMPTY_PNG = new byte[0];

	/**
	 * 获取指定 UUID 玩家的头像 PNG 字节。
	 * 若缓存中存在则直接返回，否则尝试下载并裁剪。
	 */
	public static byte[] getAvatar(MinecraftServer server, String uuidStr) {
		if (uuidStr == null || uuidStr.isEmpty()) {
			return EMPTY_PNG;
		}
		// 命中缓存
		byte[] cached = CACHE.get(uuidStr);
		if (cached != null && cached.length > 0) {
			return cached;
		}

		try {
			UUID uuid = UUID.fromString(uuidStr);
			String skinUrl = resolveSkinUrl(server, uuid);
			if (skinUrl == null) {
				return EMPTY_PNG;
			}

			BufferedImage skin = downloadImage(skinUrl);
			if (skin == null) {
				return EMPTY_PNG;
			}

			byte[] avatar = cropHead(skin);
			if (avatar.length > 0) {
				CACHE.put(uuidStr, avatar);
			}
			return avatar;
		} catch (Exception e) {
			MtrMapCommon.LOGGER.warn("获取玩家头像失败: " + uuidStr, e);
			return EMPTY_PNG;
		}
	}

	/**
	 * 从服务端的 GameProfile 解析皮肤纹理 URL。
	 */
	private static String resolveSkinUrl(MinecraftServer server, UUID uuid) {
		if (server == null) {
			return null;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		if (player == null) {
			return null;
		}
		GameProfile profile = player.getGameProfile();
		if (profile == null) {
			return null;
		}
		PropertyMap properties = profile.getProperties();
		Collection<Property> texturesProp = properties.get("textures");
		if (texturesProp == null || !texturesProp.iterator().hasNext()) {
			return null;
		}
		// authlib 6（MC 1.21）把 Property 改成了 record 风格取值
		//? if >=1.21.1 {
		/*String value = texturesProp.iterator().next().value();
		*///?} else {
		String value = texturesProp.iterator().next().getValue();
		//?}
		if (value == null) {
			return null;
		}
		try {
			String json = new String(Base64.getDecoder().decode(value));
			JsonObject root = MtrMapCommon.parseJson(json).getAsJsonObject();
			JsonObject textures = root.getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				return null;
			}
			return textures.getAsJsonObject("SKIN").get("url").getAsString();
		} catch (Exception e) {
			MtrMapCommon.LOGGER.warn("解析皮肤纹理失败: " + uuid, e);
			return null;
		}
	}

	/**
	 * 下载皮肤图片。
	 */
	private static BufferedImage downloadImage(String url) {
		// 用 HttpURLConnection 而不是 java.net.http.HttpClient：后者是 Java 11 才加的，
		// 1.16.5 的编译目标是 Java 8。
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestMethod("GET");
			if (conn.getResponseCode() != 200) {
				return null;
			}
			try (InputStream is = conn.getInputStream()) {
				return ImageIO.read(is);
			}
		} catch (Exception e) {
			MtrMapCommon.LOGGER.warn("下载皮肤失败: " + url, e);
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/**
	 * 从皮肤纹理中裁剪头部(含帽子层)，放大到 AVATAR_SIZE x AVATAR_SIZE。
	 * 皮肤布局：头部底色在 (8,8) 8x8，帽子覆盖层在 (40,8) 8x8。
	 */
	private static byte[] cropHead(BufferedImage skin) {
		try {
			BufferedImage head = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = head.createGraphics();
			// 头部底色层
			g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
			// 帽子覆盖层（仅当皮肤尺寸足够时）
			if (skin.getWidth() >= 56 && skin.getHeight() >= 16) {
				BufferedImage hat = skin.getSubimage(40, 8, 8, 8);
				g.drawImage(hat, 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
			}
			g.dispose();

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(head, "PNG", baos);
			return baos.toByteArray();
		} catch (Exception e) {
			MtrMapCommon.LOGGER.warn("裁剪头部失败", e);
			return EMPTY_PNG;
		}
	}

	/**
	 * 存储客户端推送的头像 PNG（数据源为客户端已加载的皮肤纹理，与 Chat Heads 一致）。
	 * 客户端推送优先于服务端自行下载。
	 */
	public static void putAvatar(String uuid, byte[] png) {
		if (uuid == null || png == null || png.length == 0) {
			return;
		}
		CACHE.put(uuid, png);
	}

	/**
	 * 清除头像缓存（玩家离线或重新登录时调用）。
	 */
	public static void clearCache() {
		CACHE.clear();
	}
}
