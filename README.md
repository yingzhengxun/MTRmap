# MTR Map（mtrmap）

[English](#english) | 中文

Minecraft Transit Railway（MTR）线路图模组：服务端采集 MTR 的线网 / 列车 / 玩家数据并通过内置 HTTP 服务（默认 `localhost:1145`）提供网页地图与 JSON 接口，客户端提供游戏内地图窗口（F6）与导航 HUD。

> 面向玩家的安装与使用说明在 [README_MODRINTH.md](README_MODRINTH.md)；本文件面向开发者。

## 架构总览

| 位置 | 职责 |
| --- | --- |
| 服务端 | `MapHttpServer` 提供网页与 JSON 接口（默认 1145）；`MapDataCollector` / `TrainCollector` / `PlayerTracker` 采集 MTR 数据；`RailPathFinder` 算轨道几何；`NavTaskStore` / `TripStore` 管理导航任务与行程记录 |
| 客户端 | `MapScreen` 游戏内地图窗口（F6）；`NavHud` 导航 HUD、`MapDataClient` 每 2 秒轮询地图服务；`MapEndpoint` 把「当前服务器地址 + 服务端同步过来的端口」拼成服务地址（专用服务端上不能写死 127.0.0.1） |
| 网页 | `src/main/resources/assets/mtrmap/web/`（index.html / map.js / style.css），路径查询是纯前端 Dijkstra |
| Mixin | 只注入原版 / MTR 的界面：`DashboardScreenMixin` 加「交通线路图」按钮，`GuiHudMixin` 挂 HUD 绘制。**不修改 MTR 本体** |

> **地图没有底图**：游戏内与网页都是纯色底上直接叠 MTR 线网（曾用 Xaero 世界地图当过底图，现已完全撤掉，相关代码与硬前置依赖一并移除）。
>
> **约定：网页地图与游戏内地图窗口的功能必须同步更新**——一边加了功能，另一边也要加上（图层、开关、交互都算）。

## 支持的版本与加载器

| Minecraft | 加载器 | 产物 |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<版本>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<版本>-1.21.1-universal.jar` |

1.20.1 对接 MTR 3.x，1.21.1 对接 MTR 4.x；1.21.1 没有 legacy Forge 分支，因为 MTR 在该版本只提供 Fabric / NeoForge 版。

## 构建

前置：

- **JDK 21**（1.21.1 要求 Java 21，1.20.1 的编译目标是 17）
- Gradle 8.8（仓库自带 wrapper）
- **本地依赖 jar**：`libs/` 不入库（受各自许可证约束），需要自己放进去。文件名在 `versions/<mc>/gradle.properties` 里配置：

| 文件 | 用途 |
| --- | --- |
| `mtr-fabric-1.20.1.jar` / `mtr-forge-1.20.1.jar` | 1.20.1（MTR 3.x） |
| `mtr-fabric-1.21.1.jar` / `mtr-neoforge-1.21.1.jar` | 1.21.1（MTR 4.x） |

构建两个通用 jar：

```bat
.\gradlew.bat :1.20.1:universalJar :1.21.1:universalJar --no-parallel
```

> `--no-parallel` 是必须的：三个平台模块共用同一份 `src/`，并行跑 Stonecutter 的版本生成会互相踩到工作目录。

产物位于 `build/libs/<模组版本>/<MC 版本>/universal/`。

## 项目结构

```
mtrmap/
├── src/main/                          # 公共源码（根节点的 src 就是 common）
│   ├── java/com/mtrmap/
│   │   ├── client/
│   │   │   ├── map/                   # 游戏内地图窗口：MapScreen / MapModel / MapDataClient
│   │   │   │                          #   RoutePlanner（与网页同一套算法）/ MapExporter（图片与票根导出）
│   │   │   ├── nav/                   # NavController / NavHud / NavTask
│   │   │   ├── render/GuiSink.java    # 版本无关的 GUI 绘制封装（HUD 与地图窗口共用）
│   │   │   └── MtrMapClientCommon.java# F6 开关、每 tick 入口
│   │   ├── config/                    # mods/mapconfig/mtrmap.json 读写
│   │   ├── mixin/                     # DashboardScreenMixin / hud.GuiHudMixin
│   │   ├── platform/                  # 平台与路径抽象（各加载器实现）
│   │   └── server/                    # HTTP 服务、数据采集、导航任务、行程记录
│   │       ├── MtrNetwork.java        # MTR 3.x / 4.x 差异的唯一出口（版本无关快照类型）
│   │       └── MtrRailGeometry.java   # 轨道几何
│   └── resources/
│       ├── assets/mtrmap/web/         # 网页源码
│       ├── assets/mtrmap/lang/        # 中英文语言文件
│       ├── mtrmap.mixins.json         # Mixin 配置（refmap 名固定为 mtrmap-common-refmap.json）
│       └── pack.mcmeta                # jar 根必须有，否则 Forge 丢掉整个资源包
├── fabric/  forge/  neoforge/         # 各加载器的入口、事件转接与平台实现
├── versions/<mc>/gradle.properties    # 每个 MC 版本的依赖与元数据配置
├── libs/                              # 本地依赖 jar（不入库）
├── build.gradle                       # Stonecutter centralScript：版本节点、元数据、通用 jar 打包
├── settings.gradle                    # Stonecutter 版本与加载器分支定义（0.7.11，Groovy DSL）
└── stonecutter.gradle
```

## 多版本开发

版本与加载器差异全部用 [Stonecutter](https://stonecutter.kikugie.dev/)（0.7.11，Groovy DSL）的条件注释处理：

```java
//? if >=1.21.1 {
/*ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mtrmap", "tex");
*///?} else {
ResourceLocation id = new ResourceLocation("mtrmap", "tex");
//?}
```

**写条件块时必须注意**：当前激活版本的分支就是普通源码（Stonecutter 不会为它生成中间文件），而**其它版本的分支必须整段包在 `/* ... */` 里**，因此被包裹的代码里**不能再出现 `/* */` 块注释**（内层的 `*/` 会提前闭合包裹），只能用 `//` 行注释。

跨版本差异集中在少数几个文件，上层代码不感知：

- `server/MtrNetwork.java`：MTR 3.x / 4.x 的数据模型差异（`org.mtr.mod.*` 与 `org.mtr.*`、Simulator 反射获取等）
- `client/render/GuiSink.java`：`GuiGraphics` / `VertexConsumer` 等绘制 API 差异
- `client/map/MapScreen.java`：少量界面 API 差异

## universal jar 的实现方式

采用「外壳 + 内嵌」结构，一个 MC 版本只发一个 jar：

- 外壳只有一个最小的 `@Mod("mtrmap_bridge")` 桥接类（`com/mtrmap/bridge/BridgeMod.class`）和元数据，没有任何业务代码，因此绝不会和内嵌 jar 抢同名类。
- 两个真实加载器 jar 放在 `META-INF/jars/` 下：Fabric 走 `fabric.mod.json` 的 `jars` 字段，Forge / NeoForge 走 JarJar 的 `META-INF/jarjar/metadata.json`。
- 外壳的元数据、桥接源码与编译由 `build.gradle`（Stonecutter centralScript）里的 `generateBridgeMeta` / `generateBridgeSource` / `compileBridgeMod` / `universalJar` 任务自动完成。

## 开发注意事项（踩过的坑）

- **Mixin refmap 名字**：`mtrmap.mixins.json` 里的 `refmap` 只能写死一个名字，而 Loom 给 common 生成的 refmap 带 MC 版本后缀（`mtrmap-common-1.20.1-refmap.json`）。所以各平台 `shadowJar` 里用 `zipTree` 再放一份改名后的拷贝（`rename "mtrmap-common-.*-refmap\\.json"`）——通过 `configurations` 合并进来的条目不走 `rename`/`eachFile`，必须手工放。
- **Forge 的 Mixin 注册**：Forge 只读 jar 内 `MANIFEST.MF` 的 `MixinConfigs` 属性（`mods.toml` 里的 `[[mixins]]` 是 NeoForge 专用），所以 Forge 的 `shadowJar` 里要显式写 manifest 属性。
- **`pack.mcmeta` 必须位于 jar 根**，否则 Forge 会丢弃整个资源包，语言文件不生效（仪表板按钮会显示成 `key.mtrmap.map_button` 而不是「交通线路图」）。
- **MTR 依赖范围用通配符 `*`**：MTR 3.x 的版本号自带 MC 前缀（`1.20.1-3.6.3`），写死区间会被判定为不兼容而拒绝加载；4.x 又是 `4.1.0-beta.2` 这种格式。实测 3.x / 4.x 都可用。
- **Forge 元数据的 `loaderVersion` 是 javafml 主版本**（1.20.1 填 `[47,)`），不是完整的 Forge 版本；NeoForge 的 `loaderVersion` 则是 FML loader 范围 `[1,)`。
- **专用服务端上客户端不能写死 `127.0.0.1`**：地图服务跑在服务端那台机器上，客户端既访问不到 `127.0.0.1`，也读不到服务器那份 `mtrmap.json`。所以服务端每 tick 把实际监听的端口通过自定义包（通道 `mtrmap:port`，Fabric / Forge / NeoForge 各一套实现）同步给每个在线玩家，客户端由 `client/MapEndpoint` 拼出「当前服务器地址 + 该端口」；单人游戏没有服务器地址，退回 `127.0.0.1` + 本地配置端口。`NavController`（导航/行程）与仪表板按钮同样走这个地址。
- **不要重新引入底图 / Xaero 依赖**：曾经把 Xaero 世界地图当底图（读它的显存贴图合成瓦片、上传服务端给网页用），现已完全撤掉——地图就是**纯色底上直接叠线网**。`XaeroMapTiles` / `TileTextures` / `WorldMapBridge` / `WorldMapStore`、`/api/worldmap*` 端点、`xaeroworldmap` 硬前置依赖全部删除了，不要再加回来。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 地图网页（index.html） |
| GET | `/style.css`、`/map.js` | 网页静态资源 |
| GET | `/api/data` | 车站 / 线路 / 车厂 / 列车 JSON |
| GET | `/api/overlay` | 仅线网几何（供游戏内地图窗口使用，体积更小） |
| GET | `/api/players` | 玩家位置 JSON |
| GET | `/api/whoami` | 按请求来源 IP 识别网页是哪个在线玩家打开的 |
| POST | `/api/nav` | 网页向指定玩家下发导航任务 |
| GET | `/api/nav?uuid=` | 游戏客户端轮询领取导航任务 |
| GET | `/api/trips?uuid=` | 查询某玩家的行程记录 |
| POST | `/api/trips` | 新增一条行程记录 |
| POST | `/api/trips/delete` | 删除一条行程记录 |
| GET | `/avatar/{uuid}` | 玩家头像 PNG |

## 配置

配置文件在 `mods/mapconfig/mtrmap.json`，不存在时自动创建：

```json
{
  "showDepots": true,
  "port": 1145
}
```

| 字段 | 说明 |
| --- | --- |
| `showDepots` | 是否显示车厂 |
| `port` | HTTP 服务端口，默认 `1145`。被占用时自动往后顺延到第一个可用端口，并在玩家进游戏时于消息栏提示实际端口。专用服务端上以**服务端那份配置**为准：服务端每 tick 把实际端口同步给在线玩家，客户端不用填一致 |

行程记录落盘在 `mods/mapconfig/mtrmap_trips.json`（按玩家 UUID 归档）。

命令：

```
/mtrmap showdepots <true|false>
```

## 许可证

Apache-2.0

---

<a id="english"></a>

## English

A Minecraft Transit Railway (MTR) route map mod: the server side collects MTR network / train / player data and exposes a web map plus JSON endpoints over an embedded HTTP server (default `localhost:1145`); the client side provides an in-game map window (F6) and a navigation HUD.

> Player-facing installation and usage live in [README_MODRINTH.md](README_MODRINTH.md); this document is for developers.

### Architecture

| Where | Responsibility |
| --- | --- |
| Server | `MapHttpServer` serves the web map and JSON endpoints (default 1145); `MapDataCollector` / `TrainCollector` / `PlayerTracker` collect MTR data; `RailPathFinder` computes rail geometry; `NavTaskStore` / `TripStore` hold nav tasks and trip records |
| Client | `MapScreen` is the in-game map window (F6); `NavHud` the navigation HUD, `MapDataClient` polls the map service every 2 seconds, and `MapEndpoint` builds the service URL from the current server address plus the port the server synced (never hard-code 127.0.0.1 — it breaks on dedicated servers) |
| Web | `src/main/resources/assets/mtrmap/web/` (index.html / map.js / style.css). The route planner is a pure client-side Dijkstra |
| Mixin | Only injects vanilla / MTR screens: `DashboardScreenMixin` adds the "Traffic Map" button, `GuiHudMixin` hooks HUD rendering. **The MTR mod itself is never modified** |

> **There is no base layer**: both the in-game window and the web map draw the MTR network straight onto a plain solid background (Xaero's World Map was once used as the base layer; it has been removed completely, together with its code and hard dependency).
>
> **Convention: the web map and the in-game map window must stay in sync** — whenever a feature lands on one side, add it to the other too (overlays, toggles and interactions all count).

### Supported versions

| Minecraft | Loaders | Artifact |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<version>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<version>-1.21.1-universal.jar` |

1.20.1 targets MTR 3.x, 1.21.1 targets MTR 4.x. There is no legacy Forge branch for 1.21.1 because MTR only ships Fabric / NeoForge builds for that version.

### Building

Prerequisites:

- **JDK 21** (1.21.1 requires Java 21; 1.20.1 compiles to 17)
- Gradle 8.8 (wrapper included)
- **Local dependency jars**: `libs/` is not committed (those jars are governed by their own licenses), so drop them in yourself. File names are configured in `versions/<mc>/gradle.properties`:

| File | Used for |
| --- | --- |
| `mtr-fabric-1.20.1.jar` / `mtr-forge-1.20.1.jar` | 1.20.1 (MTR 3.x) |
| `mtr-fabric-1.21.1.jar` / `mtr-neoforge-1.21.1.jar` | 1.21.1 (MTR 4.x) |

Build both universal jars:

```bat
.\gradlew.bat :1.20.1:universalJar :1.21.1:universalJar --no-parallel
```

> `--no-parallel` is required: the three platform modules share one `src/`, and running Stonecutter's version generation in parallel makes them clobber each other's working directories.

Output lands in `build/libs/<mod version>/<MC version>/universal/`.

### Project layout

```
mtrmap/
├── src/main/                          # Shared sources (the root node's src is the common module)
│   ├── java/com/mtrmap/
│   │   ├── client/
│   │   │   ├── map/                   # In-game map window: MapScreen / MapModel / MapDataClient
│   │   │   │                          #   RoutePlanner (same algorithm as the web map), MapExporter
│   │   │   ├── nav/                   # NavController / NavHud / NavTask
│   │   │   ├── render/GuiSink.java    # Version-neutral GUI drawing, shared by HUD and map window
│   │   │   └── MtrMapClientCommon.java# F6 toggle, per-tick entry point
│   │   ├── config/                    # mods/mapconfig/mtrmap.json read/write
│   │   ├── mixin/                     # DashboardScreenMixin / hud.GuiHudMixin
│   │   ├── platform/                  # Platform and path abstraction (per-loader implementations)
│   │   └── server/                    # HTTP server, data collection, nav tasks, trip storage
│   │       ├── MtrNetwork.java        # The single exit point for MTR 3.x / 4.x differences
│   │       └── MtrRailGeometry.java   # Rail geometry
│   └── resources/
│       ├── assets/mtrmap/web/         # Web sources
│       ├── assets/mtrmap/lang/        # Chinese and English lang files
│       ├── mtrmap.mixins.json         # Mixin config (refmap pinned to mtrmap-common-refmap.json)
│       └── pack.mcmeta                # Must sit at the jar root or Forge drops the whole resource pack
├── fabric/  forge/  neoforge/         # Loader entry points, event bridges and platform implementations
├── versions/<mc>/gradle.properties    # Per-MC dependency and metadata config
├── libs/                              # Local dependency jars (not committed)
├── build.gradle                       # Stonecutter centralScript: version nodes, metadata, universal jar
├── settings.gradle                    # Stonecutter version and loader branches (0.7.11, Groovy DSL)
└── stonecutter.gradle
```

### Multi-version development

All version and loader differences go through [Stonecutter](https://stonecutter.kikugie.dev/) (0.7.11, Groovy DSL) conditional comments:

```java
//? if >=1.21.1 {
/*ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mtrmap", "tex");
*///?} else {
ResourceLocation id = new ResourceLocation("mtrmap", "tex");
//?}
```

**Careful when writing these blocks**: the branch for the currently active version is plain source code (Stonecutter generates no intermediate file for it), while **every other version's branch must be wrapped in `/* ... */`**. As a result the wrapped code **may not contain `/* */` block comments** (an inner `*/` closes the wrapper early) — use `//` line comments only.

Version differences are concentrated in a few files so upper layers stay unaware:

- `server/MtrNetwork.java`: MTR 3.x / 4.x data model differences (`org.mtr.mod.*` vs `org.mtr.*`, reflective access to the Simulator, ...)
- `client/render/GuiSink.java`: drawing API differences (`GuiGraphics`, `VertexConsumer`, ...)
- `client/map/MapScreen.java`: a few screen API differences

### How the universal jar works

A "shell + embedded jars" layout, one jar per MC version:

- The shell contains only a minimal `@Mod("mtrmap_bridge")` bridge class (`com/mtrmap/bridge/BridgeMod.class`) plus metadata and no business code, so it can never clash with the embedded jars over class names.
- The two real loader jars live under `META-INF/jars/`: Fabric loads them via the `jars` field in `fabric.mod.json`, Forge / NeoForge via JarJar's `META-INF/jarjar/metadata.json`.
- The shell's metadata, bridge source and compilation are produced automatically by the `generateBridgeMeta` / `generateBridgeSource` / `compileBridgeMod` / `universalJar` tasks in `build.gradle` (the Stonecutter centralScript).

### Gotchas

- **Mixin refmap name**: the `refmap` in `mtrmap.mixins.json` must be a fixed name, but Loom generates the common refmap with an MC-version suffix (`mtrmap-common-1.20.1-refmap.json`). Each platform's `shadowJar` therefore copies a renamed duplicate in via `zipTree` (`rename "mtrmap-common-.*-refmap\\.json"`) — entries merged through `configurations` bypass `rename` / `eachFile`, so it has to be done by hand.
- **Forge mixin registration**: Forge only reads the `MixinConfigs` attribute in the jar's `MANIFEST.MF` (the `[[mixins]]` block in `mods.toml` is NeoForge-only), so Forge's `shadowJar` sets that manifest attribute explicitly.
- **`pack.mcmeta` must be at the jar root**, otherwise Forge drops the whole resource pack and the lang files never load (the dashboard button then shows `key.mtrmap.map_button` instead of "Traffic Map").
- **Declare the MTR dependency range as the wildcard `*`**: MTR 3.x reports versions with an MC prefix (`1.20.1-3.6.3`), so a hard-coded range is judged out of range and refuses to load, while 4.x uses formats like `4.1.0-beta.2`. Both 3.x and 4.x are verified to work.
- **Forge metadata's `loaderVersion` is the javafml major version** (`[47,)` for 1.20.1), not a full Forge version; NeoForge's `loaderVersion` is the FML loader range `[1,)`.
- **Never hard-code `127.0.0.1` on the client (dedicated servers)**: the map service runs on the server machine, so a client can neither reach `127.0.0.1` nor read the server's `mtrmap.json`. The server therefore syncs its actual port to every online player via a custom packet (channel `mtrmap:port`, with a Fabric / Forge / NeoForge implementation each) and the client's `client/MapEndpoint` builds `<current server address>:<port>`; single-player has no server address and falls back to `127.0.0.1` plus the local config port. `NavController` (navigation / trips) and the dashboard button use that same address.
- **Do not reintroduce a base layer / Xaero dependency**: Xaero's World Map was once used as the base layer (reading its GPU textures, assembling tiles and uploading them to the server for the web map); it has been removed completely — the map is now **the network drawn straight onto a plain solid background**. `XaeroMapTiles` / `TileTextures` / `WorldMapBridge` / `WorldMapStore`, the `/api/worldmap*` endpoints and the `xaeroworldmap` hard dependency are all gone; do not add them back.

### HTTP endpoints

| Method | Path | Description |
| --- | --- | --- |
| GET | `/` | Map page (index.html) |
| GET | `/style.css`, `/map.js` | Web assets |
| GET | `/api/data` | Stations / lines / depots / trains as JSON |
| GET | `/api/overlay` | Network geometry only (smaller payload, for the in-game map window) |
| GET | `/api/players` | Player positions as JSON |
| GET | `/api/whoami` | Identifies which online player opened the page, based on the request source IP |
| POST | `/api/nav` | Web map dispatches a navigation task to a given player |
| GET | `/api/nav?uuid=` | In-game client polls for its navigation task |
| GET | `/api/trips?uuid=` | Query a player's trip history |
| POST | `/api/trips` | Add a trip record |
| POST | `/api/trips/delete` | Delete a trip record |
| GET | `/avatar/{uuid}` | Player avatar PNG |

### Configuration

The config file lives at `mods/mapconfig/mtrmap.json` and is created automatically if missing:

```json
{
  "showDepots": true,
  "port": 1145
}
```

| Field | Description |
| --- | --- |
| `showDepots` | Whether to display depots |
| `port` | HTTP server port, defaults to `1145`. If it is taken, the server shifts to the next free port and notifies players in chat with the actual port. On a dedicated server the **server's copy wins**: the server syncs the actual port to every online player, so clients do not have to match it |

Trip records are persisted to `mods/mapconfig/mtrmap_trips.json` (archived per player UUID).

Command:

```
/mtrmap showdepots <true|false>
```

### License

Apache-2.0
