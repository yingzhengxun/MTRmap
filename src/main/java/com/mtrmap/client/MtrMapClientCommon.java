package com.mtrmap.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端头像采集（与加载器无关的通用逻辑）。
 *
 * 通过 Minecraft 原生的皮肤加载机制触发皮肤加载（1.20.1 及以前是 SkinManager.registerSkins，
 * 1.21 起换成 SkinManager.getOrLoad），
 * 这条路径与 Chat Heads 模组的皮肤数据源完全一致（均取自客户端
 * 已加载的玩家皮肤纹理）。皮肤就绪后，按 Chat Heads 相同的公式
 * 混合"头部底色层 + 帽子覆盖层"，生成 64x64 头像 PNG，并通过
 * {@link Platform#sendAvatarToServer} 推送给服务端，由服务端 /avatar 端点提供。
 *
 * 仅当本地存在游戏客户端时（单人/局域网联机）才会生效；
 * 纯专用服务器没有客户端，仍由服务端 AvatarHandler 回退自行下载皮肤。
 *
 * 具体的事件注册（tick / 断开连接）由平台模块负责，这里只暴露回调。
 */
public final class MtrMapClientCommon {

    private static final int AVATAR_SIZE = 64;

    /** uuid -> 皮肤纹理位置（等待纹理就绪后提取头像） */
    private static final Map<UUID, ResourceLocation> PENDING = new ConcurrentHashMap<>();
    /** 已成功推送给服务端的玩家 */
    private static final Set<UUID> SENT = ConcurrentHashMap.newKeySet();
    private static int tickCount = 0;

    private MtrMapClientCommon() {
    }

    /** 客户端初始化：启动游戏内导航的进度轮询。 */
    public static void init() {
        com.mtrmap.client.nav.NavController.start();
    }

    /** 客户端每个 tick 末调用。 */
    public static void onClientTick(Minecraft client) {
        // 导航进度判定 / Ctrl+X 退出（放在最前，断开连接后内部会自行跳过）
        com.mtrmap.client.nav.NavController.onClientTick(client);
        // F6 打开 / 关闭游戏内地图窗口
        handleMapKey(client);
        if (client.getConnection() == null || client.level == null) {
            return;
        }
        // 每 40 tick（2 秒）触发一次皮肤注册
        if (++tickCount % 40 == 0) {
            triggerSkinLoads(client);
        }
        processPending(client);
    }

    /**
     * F6 开关地图窗口。
     *
     * <p>用「这一 tick 按下、上一 tick 没按」做边沿判定，避免按住 F6 时窗口疯狂开关；
     * 只在没有任何界面、或当前界面就是地图窗口时响应（搜索框聚焦时由窗口自己吞掉 F6）。
     */
    private static void handleMapKey(Minecraft client) {
        boolean down = InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_F6);
        boolean pressed = down && !f6WasDown;
        f6WasDown = down;
        if (!pressed) {
            return;
        }
        if (client.screen instanceof com.mtrmap.client.map.MapScreen) {
            MtrMapCommon.LOGGER.info("F6：关闭地图窗口");
            client.setScreen(null);
        } else if (client.screen == null) {
            MtrMapCommon.LOGGER.info("F6：打开地图窗口");
            client.setScreen(new com.mtrmap.client.map.MapScreen());
        } else {
            MtrMapCommon.LOGGER.info("F6：当前界面是 {}，先关掉它再按 F6", client.screen.getClass().getSimpleName());
        }
    }

    /**
     * 收到服务端回传的地图数据分块（由平台模块的客户端接收器调用）。
     *
     * <p>收到的可能是任意一个分块（顺序由网络保证），拼装与配对交给 {@link MapChannel}。
     */
    public static void onMapDataReceived(int requestId, int index, int total, byte[] chunk) {
        com.mtrmap.client.MapChannel.onChunk(requestId, index, total, chunk);
    }

    /** F6 上一 tick 是否按下（边沿判定用） */
    private static boolean f6WasDown;

    /** 断开连接时清空状态。 */
    public static void onDisconnect() {
        SENT.clear();
        PENDING.clear();
        com.mtrmap.client.nav.NavController.onDisconnect();
        com.mtrmap.client.map.MapDataClient.onDisconnect();
        // 忘掉上一个服务器同步过来的地图端口，下次进服重新同步
        MtrMapCommon.clearMapPort();
        f6WasDown = false;
    }

    /**
     * 为尚未处理的在线玩家触发皮肤加载。
     * 离线服玩家没有皮肤时 registerSkins 不会回调，静默跳过。
     */
    private static void triggerSkinLoads(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return;
        }
        SkinManager skinManager = client.getSkinManager();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            if (profile == null || profile.getId() == null) {
                continue;
            }
            UUID uuid = profile.getId();
            if (SENT.contains(uuid) || PENDING.containsKey(uuid)) {
                continue;
            }
            //? if >=1.21.1 {
            /*// 1.21 移除了 registerSkins：改成异步取回皮肤，拿到纹理位置后照旧从 TextureManager 读像素
            skinManager.getOrLoad(profile).thenAccept(skin -> {
                ResourceLocation texture = skin.texture();
                if (texture != null) {
                    client.execute(() -> PENDING.put(uuid, texture));
                }
            });
            *///?} else {
            skinManager.registerSkins(profile, (type, location, texture) -> {
                if (type == MinecraftProfileTexture.Type.SKIN) {
                    PENDING.put(uuid, location);
                }
            }, false);
            //?}
        }
    }

    /**
     * 检查等待中的皮肤纹理是否已就绪，就绪则提取头像并推送给服务端。
     */
    private static void processPending(Minecraft client) {
        if (PENDING.isEmpty()) {
            return;
        }
        TextureManager textureManager = client.getTextureManager();
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, ResourceLocation> entry : PENDING.entrySet()) {
            AbstractTexture texture = textureManager.getTexture(entry.getValue());
            // 不用 instanceof 模式变量：1.16.5 的编译目标是 Java 8
            if (!(texture instanceof DynamicTexture)) {
                continue;
            }
            NativeImage image = ((DynamicTexture) texture).getPixels();
            if (image == null) {
                continue;
            }
            byte[] png = buildHeadPng(image);
            if (png != null && png.length > 0) {
                sendAvatar(entry.getKey(), png);
                SENT.add(entry.getKey());
                done.add(entry.getKey());
            }
        }
        done.forEach(PENDING::remove);
    }

    /**
     * 从皮肤纹理中提取"头部底色层 + 帽子覆盖层"的混合头像，输出 64x64 PNG。
     * 提取与混合公式与 Chat Heads 的 extractBlendedHead / blendColors 保持一致。
     */
    private static byte[] buildHeadPng(NativeImage skin) {
        // 合法的皮肤宽度至少为 64，过小的纹理（如 1x1 缺失纹理）直接忽略
        if (skin.getWidth() < 64 || skin.getHeight() < 32) {
            return null;
        }
        try {
            // 兼容旧版 64x32 皮肤与高清皮肤（128x128 等）
            boolean isLegacy = skin.getWidth() / 2 == skin.getHeight();
            int xScale = skin.getWidth() / 64;
            int yScale = skin.getHeight() / (isLegacy ? 32 : 64);
            if (xScale < 1 || yScale < 1) {
                return null;
            }
            int headWidth = 8 * xScale;
            int headHeight = 8 * yScale;

            BufferedImage head = new BufferedImage(headWidth, headHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < headHeight; y++) {
                for (int x = 0; x < headWidth; x++) {
                    int headColor = skin.getPixelRGBA(8 * xScale + x, 8 * yScale + y);
                    int hatColor = skin.getPixelRGBA(40 * xScale + x, 8 * yScale + y);
                    head.setRGB(x, y, abgrToArgb(blendColors(headColor, hatColor)));
                }
            }

            // 放大到 64x64
            BufferedImage scaled = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(head, 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            MtrMapCommon.LOGGER.warn("生成玩家头像失败", e);
            return null;
        }
    }

    /**
     * 将 hat 图层混合到 head 图层上，公式与 Chat Heads 的 blendColors 相同。
     * 输入为 NativeImage 的 ABGR 像素，输出为 ARGB 像素。
     */
    private static int blendColors(int headColor, int hatColor) {
        float a1 = ((headColor >>> 24) & 0xFF) / 255f;
        float r1 = (headColor & 0xFF) / 255f;
        float g1 = ((headColor >>> 8) & 0xFF) / 255f;
        float b1 = ((headColor >>> 16) & 0xFF) / 255f;

        float a2 = ((hatColor >>> 24) & 0xFF) / 255f;
        float r2 = (hatColor & 0xFF) / 255f;
        float g2 = ((hatColor >>> 8) & 0xFF) / 255f;
        float b2 = ((hatColor >>> 16) & 0xFF) / 255f;

        // hat 透明度为 1 时取 hat，为 0 时取 head
        float a3 = a2 * a2 + (1 - a2) * a1;
        float r3 = a2 * r2 + (1 - a2) * r1;
        float g3 = a2 * g2 + (1 - a2) * g1;
        float b3 = a2 * b2 + (1 - a2) * b1;

        return ((int) (a3 * 255f) << 24)
                | ((int) (r3 * 255f) << 16)
                | ((int) (g3 * 255f) << 8)
                | (int) (b3 * 255f);
    }

    /** NativeImage 的 ABGR 像素转为标准 ARGB */
    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 将头像 PNG 推送给服务端（具体协议由平台实现） */
    private static void sendAvatar(UUID uuid, byte[] png) {
        try {
            Platform.get().sendAvatarToServer(uuid, png);
            MtrMapCommon.LOGGER.debug("已推送玩家头像: {}", uuid);
        } catch (Exception e) {
            MtrMapCommon.LOGGER.warn("推送玩家头像失败: {}", uuid, e);
        }
    }
}
