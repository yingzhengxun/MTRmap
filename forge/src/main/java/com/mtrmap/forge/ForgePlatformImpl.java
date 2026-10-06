package com.mtrmap.forge;

import com.mtrmap.platform.MtrMapPlatform;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Forge 平台实现。
 *
 * <p>{@link #isModLoaded(String)} 需要兼容"构造期就调用"的场景：那时 {@link ModList#get()}
 * 可能还没准备好，故先回退到更早可用的 {@link LoadingModList}，两条路径都用 try/catch 兜底，
 * 保证任何情况下都不会 NPE。
 */
public class ForgePlatformImpl implements MtrMapPlatform {

    @Override
    public String loaderName() {
        return "forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        try {
            ModList modList = ModList.get();
            if (modList != null) {
                return modList.isLoaded(modId);
            }
        } catch (Throwable ignored) {
            // ModList 尚未就绪，走下面的兜底
        }
        try {
            LoadingModList loadingModList = LoadingModList.get();
            if (loadingModList != null && loadingModList.getModFileById(modId) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // 更早期阶段，无法判断
        }
        return false;
    }

    @Override
    public Path getConfigDir() {
        // 与 Fabric 的 getGameDir() 语义一致：游戏目录，最终解析出 mods/mapconfig/mtrmap.json
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public void sendAvatarToServer(UUID uuid, byte[] png) {
        ForgeNetwork.sendAvatarToServer(uuid, png);
    }

    @Override
    public void sendMapPort(net.minecraft.server.level.ServerPlayer player, int port) {
        ForgeNetwork.sendMapPort(player, port);
    }
}
