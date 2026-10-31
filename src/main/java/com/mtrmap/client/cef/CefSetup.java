package com.mtrmap.client.cef;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 内置 MCEF 的离线准备。
 *
 * <p>MCEF 默认会在启动时联网下载 java-cef 原生库（也就是「自动更新」）并解到
 * {@code mods/mcef-libraries/平台/}，网络不通时 MCEF 会直接初始化失败。本模组把
 * Windows x64 的原生库随 jar 一起打包（{@code rbmm-cef/windows_amd64/}），
 * 启动时先解到 MCEF 期望的目录，再给 MCEF 一份配置：skip-download=true 跳过原生库下载，
 * download-mirror 指向本进程内的 127.0.0.1 小服务（MCEF 每次启动仍会拉一次校验和，
 * 这一步无法从配置里关掉，指向本地既能离线走通又不会产生任何外网流量）。
 *
 * <p>只在 Windows x64 上生效；其它平台保持 MCEF 的默认行为。
 */
public final class CefSetup {

    /** 与 MCEFPlatform.getNormalizedName() 一致（枚举名转小写） */
    private static final String PLATFORM = "windows_amd64";
    /** MCEF 期望在 jcef.path 下看到的文件名（见 mcef2.json 的 win64 列表） */
    private static final String[] NATIVE_FILES = {
            "cef.pak", "cef_100_percent.pak", "cef_200_percent.pak", "cef_extensions.pak",
            "chrome_elf.dll", "d3dcompiler_47.dll", "devtools_resources.pak", "icudtl.dat",
            "jcef.dll", "jcef_helper.exe", "libcef.dll", "libEGL.dll", "libGLESv2.dll",
            "locales.zip", "natives_blob.bin", "snapshot_blob.bin", "v8_context_snapshot.bin"
    };
    /** 本地镜像返回的假校验和：只为让 MCEF 的「检查更新」在离线时也能走完 */
    private static final String CHECKSUM =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String READY_MARKER = ".rbmm-cef-ready";

    private static boolean prepared;
    private static HttpServer mirror;

    private CefSetup() {
    }

    /** 客户端入口初始化时调用一次（早于 MCEF 自己开始下载）。 */
    public static synchronized void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        try {
            if (!isWindowsAmd64()) {
                return;
            }
            Path gameDir = Platform.getConfigDir();
            Path nativeDir = gameDir.resolve("mods").resolve("mcef-libraries").resolve(PLATFORM);
            boolean nativesReady = ensureNatives(gameDir, nativeDir);
            int port = startMirror();
            writeSettings(gameDir, nativesReady && port > 0, port);
            MtrMapCommon.LOGGER.info("内置 MCEF 准备完成：原生库 {}，本地校验镜像端口 {}",
                    nativesReady ? "已就绪" : "缺失", port);
        } catch (Throwable t) {
            MtrMapCommon.LOGGER.warn("内置 MCEF 的离线准备失败，回退到 MCEF 默认下载流程", t);
        }
    }

    private static boolean isWindowsAmd64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("windows") && arch.equals("amd64");
    }

    // ===== 原生库 =====

    private static boolean ensureNatives(Path gameDir, Path nativeDir) throws Exception {
        if (Files.isRegularFile(nativeDir.resolve(READY_MARKER)) && Files.isRegularFile(nativeDir.resolve("libcef.dll"))) {
            return true;
        }
        Files.createDirectories(nativeDir);
        ZipFile bundled = findBundledJar(gameDir);
        if (bundled != null) {
            try {
                for (String name : NATIVE_FILES) {
                    ZipEntry entry = bundled.getEntry("rbmm-cef/" + PLATFORM + "/" + name);
                    if (entry == null) {
                        return false;
                    }
                    try (InputStream in = bundled.getInputStream(entry)) {
                        Files.copy(in, nativeDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.write(nativeDir.resolve(READY_MARKER), new byte[0]);
                return true;
            } finally {
                bundled.close();
            }
        }
        // 兜底：直接按类路径资源读取（单装平台 jar 且资源可见时）
        boolean allCopied = true;
        for (String name : NATIVE_FILES) {
            String resource = "rbmm-cef/" + PLATFORM + "/" + name;
            try (InputStream in = CefSetup.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    allCopied = false;
                    break;
                }
                Files.copy(in, nativeDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (allCopied) {
            Files.write(nativeDir.resolve(READY_MARKER), new byte[0]);
        }
        return allCopied;
    }

    /** 在 mods 目录里找那份装了原生库的 jar（通用 jar 的外壳）。 */
    private static ZipFile findBundledJar(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : stream) {
                try {
                    ZipFile zip = new ZipFile(jar.toFile());
                    if (zip.getEntry("rbmm-cef/" + PLATFORM + "/libcef.dll") != null) {
                        return zip;
                    }
                    zip.close();
                } catch (Exception ignored) {
                    // 不是 zip / 读不了就跳过
                }
            }
        } catch (Exception ignored) {
            // 目录读不了就算了
        }
        return null;
    }

    // ===== 本地镜像 =====

    private static int startMirror() {
        try {
            if (mirror != null) {
                return mirror.getAddress().getPort();
            }
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = CHECKSUM.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            mirror = server;
            return server.getAddress().getPort();
        } catch (Throwable t) {
            MtrMapCommon.LOGGER.warn("内置 MCEF 的本地校验镜像启动失败", t);
            return -1;
        }
    }

    // ===== MCEF 配置 =====

    private static void writeSettings(Path gameDir, boolean offlineReady, int port) throws Exception {
        Path dir = gameDir.resolve("config").resolve("mcef");
        Files.createDirectories(dir);
        Properties props = new Properties();
        if (offlineReady) {
            props.setProperty("skip-download", "true");
            props.setProperty("download-mirror", "http://127.0.0.1:" + port);
        } else {
            props.setProperty("skip-download", "false");
        }
        props.setProperty("user-agent", "null");
        props.setProperty("use-cache", "false");
        Path file = dir.resolve("mcef.properties");
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "generated by Ruochu's better MTR map");
        }
    }
}