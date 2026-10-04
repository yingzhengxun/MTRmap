# MTR Map（mtrmap）

[English](#english) | 中文

通过 `localhost:1145` 显示 Minecraft Transit Railway（MTR）的线路地图，包含车站、线路、车厂与玩家位置，并提供网页端路径查询、实时导航下发与行程记录。

## 支持的版本与加载器

| Minecraft | 加载器 | 通用 jar |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<版本>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<版本>-1.21.1-universal.jar` |

每个 MC 版本只产出一个 `universal` jar，同一个 jar 在对应的两个加载器下都能直接加载，不需要区分安装。

> 1.21.1 没有 legacy Forge 分支，因为 MTR 在该版本只提供 Fabric / NeoForge 版。1.20.1 对接 MTR 3.x，1.21.1 对接 MTR 4.x。

## 功能

### 网页地图（浏览器打开 `http://localhost:<端口>`）

- 画布绘制线路、车站、换乘站、车厂与列车位置；换乘站用跑道形（胶囊）标记，范围覆盖该站所有线路的站台。
- 车站/车厂名称支持 `主名|译名` 两行显示；上下行同名线路自动合并为一条。
- **路径查询**：纯前端 Dijkstra（在「线路:车站」图上计算），给出多种方案，标注换乘次数与乘车方向（`开往 <终点站>`），连续步行段合并为一段。
- **我的位置**：每 2 秒刷新，位置变化会自动重绘地图、更新起点并重新查询路线；退出游戏后自动隐藏头像卡片。
- **导航下发**：选定方案后把乘车步骤推送给游戏内客户端。
- **行程记录**：按玩家存档，可删除某条记录，并生成纪念票根（PNG 下载 / 打印页面）。
- 夜间模式、中英文切换、导出图片（PNG / JPG / WebP，可选质量）、地图字体选择、缩放与重置视图、工具栏折叠。

### 游戏内

- 在 MTR 铁路仪表板上注入「交通线路图」按钮，点击后用系统浏览器打开网页地图。
- Xaero 世界地图叠加层：在世界地图上叠加 MTR 线网（可在配置中开关，另有地图侧开关按钮）。
- 导航 HUD 面板：显示当前行程进度；`Ctrl+X`（无界面打开时）退出导航；完成或退出时上传行程记录。

## 安装

1. 安装 Minecraft 对应版本、加载器，以及 **MTR 模组**（3.x 与 4.x 均可，依赖范围声明为通配符）。
2. Fabric 版还需安装 **Fabric API**（Forge / NeoForge 版不需要）。
3. 把对应 MC 版本的 `mtrmap-<版本>-<MC>-universal.jar` 放进 `mods/`。
4. 可选：安装 Xaero 的世界地图以启用地图叠加层。
5. 启动游戏 / 服务器，浏览器打开 `http://localhost:1145`，或在铁路仪表板点「交通线路图」。

## 配置

配置文件在 `mods/mapconfig/mtrmap.json`，不存在时自动创建：

```json
{
  "showDepots": true,
  "port": 1145,
  "xaeroOverlay": true
}
```

| 字段 | 说明 |
| --- | --- |
| `showDepots` | 是否显示车厂 |
| `port` | HTTP 服务端口，默认 `1145` |
| `xaeroOverlay` | 是否在 Xaero 世界地图上叠加 MTR 线网 |

行程记录落盘在 `mods/mapconfig/mtrmap_trips.json`（按玩家 UUID 归档，网页端展示与删除）。

### 命令

```
/mtrmap showdepots <true|false>
```

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 地图网页（index.html） |
| GET | `/style.css`、`/map.js` | 网页静态资源 |
| GET | `/api/data` | 车站 / 线路 / 车厂 / 列车 JSON |
| GET | `/api/overlay` | 仅线网几何（供 Xaero 叠加层使用，体积更小） |
| GET | `/api/players` | 玩家位置 JSON |
| GET | `/api/whoami` | 按请求来源 IP 识别网页是哪个在线玩家打开的 |
| POST | `/api/nav` | 网页向指定玩家下发导航任务 |
| GET | `/api/nav?uuid=` | 游戏客户端轮询领取导航任务 |
| GET | `/api/trips?uuid=` | 查询某玩家的行程记录 |
| POST | `/api/trips` | 新增一条行程记录 |
| POST | `/api/trips/delete` | 删除一条行程记录 |
| GET | `/avatar/{uuid}` | 玩家头像 PNG |

## 构建

一条命令构建两个通用 jar：

```bat
.\gradlew.bat :1.20.1:universalJar :1.21.1:universalJar
```

产物位于 `build/libs/<模组版本>/<MC 版本>/universal/`。

### universal jar 的实现方式

采用「外壳 + 内嵌」结构：

- 外壳只包含一个最小的 `@Mod("mtrmap_bridge")` 桥接类（`com/mtrmap/bridge/BridgeMod.class`）和元数据，本身不含业务代码，因此不会与内嵌 jar 产生类名冲突。
- 两个真实加载器 jar 放在 `META-INF/jars/` 下：Fabric 通过 `fabric.mod.json` 的 `jars` 字段加载，Forge / NeoForge 通过 JarJar 的 `META-INF/jarjar/metadata.json` 加载。
- 外壳的元数据、桥接源码与编译均由 `build.gradle`（Stonecutter centralScript）中的 `generateBridgeMeta` / `generateBridgeSource` / `compileBridgeMod` / `universalJar` 任务自动完成。

> 说明：每个版本都发布 universal jar，不再需要分加载器的 `buildAndCollect`。

## 目录结构

```
mtrmap/
├── src/main/                     # 公共源码（根节点的 src 即 common）
│   ├── java/com/mtrmap/
│   │   ├── client/               # 客户端公共逻辑、导航 HUD、Xaero 叠加层渲染
│   │   ├── config/               # mtrmap.json 读写
│   │   ├── mixin/                # Mixin 注入（仪表板按钮、HUD 等）
│   │   ├── platform/             # 平台/路径抽象
│   │   └── server/               # HTTP 服务、数据采集、导航任务、行程记录
│   └── resources/
│       ├── assets/mtrmap/web/    # 网页源码：index.html / map.js / style.css
│       ├── assets/mtrmap/lang/   # 中英文语言文件
│       └── mtrmap.mixins.json
├── fabric/  forge/  neoforge/    # 各加载器的入口、事件转接与平台实现
├── versions/                     # 每个 MC 版本一份依赖配置（versions/<mc>/gradle.properties）
├── libs/                         # 本地依赖 jar（MTR、Xaero，按文件名在各版本配置中引用）
├── build.gradle                  # Stonecutter centralScript：版本节点与通用 jar 打包
├── stonecutter.gradle            # 版本/加载器分支定义
└── settings.gradle
```

## 技术说明

- 多版本适配由 [Stonecutter](https://stonecutter.kikugie.dev/) 管理，版本差异通过源码中的 `//? if <版本> { ... //?}` 条件块处理。
- 不修改 MTR 本体，全部功能通过 Mixin 注入实现。
- MTR 数据模型访问集中在 `src/main/java/com/mtrmap/server/MtrNetwork.java`，对上层暴露与版本无关的快照类型，屏蔽 3.x / 4.x 的 API 差异。

## 许可证

Apache-2.0

---

<a id="english"></a>

## English

Displays the Minecraft Transit Railway (MTR) network on `localhost:1145`, including stations, lines, depots and player positions, plus a web-based route planner, in-game navigation dispatch and trip history.

### Supported Versions & Loaders

| Minecraft | Loaders | Universal jar |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<version>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<version>-1.21.1-universal.jar` |

Each Minecraft version ships a single `universal` jar that loads directly on both of its loaders — no per-loader build to pick.

> There is no legacy Forge branch for 1.21.1, because MTR only provides Fabric / NeoForge builds for that version. 1.20.1 targets MTR 3.x, 1.21.1 targets MTR 4.x.

### Features

#### Web map (open `http://localhost:<port>` in a browser)

- Canvas rendering of lines, stations, interchange stations, depots and trains. Interchange stations use a stadium/capsule marker whose extent covers the platform midpoints of all lines serving that station.
- Station and depot names support two-line display via `main|translation`. Up and down lines sharing the same name are merged into a single line.
- **Route planner**: client-side Dijkstra over a "line:station" graph, offering multiple options with transfer counts and ride direction (`towards <terminus>`); consecutive walking legs are merged into one.
- **My Location**: refreshed every 2 seconds; position changes automatically redraw the map, update the origin and re-run the route query. The avatar card is hidden once you leave the game.
- **Navigation dispatch**: pushing the selected itinerary to the in-game client.
- **Trip history**: archived per player, with individual deletion and a commemorative ticket (PNG download / print page).
- Dark mode, Chinese/English toggle, image export (PNG / JPG / WebP with selectable quality), map font selector, zoom and view reset, collapsible toolbar.

#### In game

- Injects a "Route Map" button into MTR's railway dashboard that opens the web map in the system browser.
- Xaero world map overlay: renders the MTR network on top of the world map (toggleable in the config, plus an on-map button).
- Navigation HUD panel showing current trip progress. `Ctrl+X` (with no screen open) exits navigation; the trip is uploaded on completion or exit.

### Installation

1. Install the matching Minecraft version, loader, and the **MTR mod** (both 3.x and 4.x work — the dependency range is declared as a wildcard).
2. The Fabric build additionally requires **Fabric API** (the Forge / NeoForge builds do not).
3. Drop the `mtrmap-<version>-<MC>-universal.jar` for your MC version into `mods/`.
4. Optional: install Xaero's World Map to enable the map overlay.
5. Launch the game / server, then open `http://localhost:1145` in a browser, or click the Route Map button in the railway dashboard.

### Configuration

The config file lives at `mods/mapconfig/mtrmap.json` and is created automatically if missing:

```json
{
  "showDepots": true,
  "port": 1145,
  "xaeroOverlay": true
}
```

| Field | Description |
| --- | --- |
| `showDepots` | Whether to display depots |
| `port` | HTTP server port, defaults to `1145` |
| `xaeroOverlay` | Whether to overlay the MTR network on the Xaero world map |

Trip records are persisted to `mods/mapconfig/mtrmap_trips.json` (archived per player UUID; shown and deletable from the web map).

#### Command

```
/mtrmap showdepots <true|false>
```

### HTTP Endpoints

| Method | Path | Description |
| --- | --- | --- |
| GET | `/` | Map page (index.html) |
| GET | `/style.css`, `/map.js` | Web assets |
| GET | `/api/data` | Stations / lines / depots / trains as JSON |
| GET | `/api/overlay` | Network geometry only (smaller payload, for the Xaero overlay) |
| GET | `/api/players` | Player positions as JSON |
| GET | `/api/whoami` | Identifies which online player opened the page, based on the request source IP |
| POST | `/api/nav` | Web map dispatches a navigation task to a given player |
| GET | `/api/nav?uuid=` | In-game client polls for its navigation task |
| GET | `/api/trips?uuid=` | Query a player's trip history |
| POST | `/api/trips` | Add a trip record |
| POST | `/api/trips/delete` | Delete a trip record |
| GET | `/avatar/{uuid}` | Player avatar PNG |

### Building

Build both universal jars with a single command:

```bat
.\gradlew.bat :1.20.1:universalJar :1.21.1:universalJar
```

Output lands in `build/libs/<mod version>/<MC version>/universal/`.

#### How the universal jar works

It uses a "shell + embedded jars" layout:

- The shell contains only a minimal `@Mod("mtrmap_bridge")` bridge class (`com/mtrmap/bridge/BridgeMod.class`) plus metadata, and holds no business code, so it can never clash with the embedded jars over class names.
- The two real loader jars are placed under `META-INF/jars/`: Fabric loads them via the `jars` field in `fabric.mod.json`, while Forge / NeoForge use JarJar's `META-INF/jarjar/metadata.json`.
- The shell's metadata, bridge source and compilation are all produced automatically by the `generateBridgeMeta` / `generateBridgeSource` / `compileBridgeMod` / `universalJar` tasks in `build.gradle` (the Stonecutter centralScript).

> Note: every version publishes a universal jar, so the per-loader `buildAndCollect` task is no longer needed.

### Project Layout

```
mtrmap/
├── src/main/                     # Shared sources (the root node's src is the common module)
│   ├── java/com/mtrmap/
│   │   ├── client/               # Shared client logic, navigation HUD, Xaero overlay rendering
│   │   ├── config/               # mtrmap.json read/write
│   │   ├── mixin/                # Mixin injections (dashboard button, HUD, ...)
│   │   ├── platform/             # Platform / path abstraction
│   │   └── server/               # HTTP server, data collection, nav tasks, trip storage
│   └── resources/
│       ├── assets/mtrmap/web/    # Web sources: index.html / map.js / style.css
│       ├── assets/mtrmap/lang/   # Chinese and English lang files
│       └── mtrmap.mixins.json
├── fabric/  forge/  neoforge/    # Loader entry points, event bridges and platform implementations
├── versions/                     # One dependency config per MC version (versions/<mc>/gradle.properties)
├── libs/                         # Local dependency jars (MTR, Xaero; referenced by file name in each version config)
├── build.gradle                  # Stonecutter centralScript: version nodes and universal jar packaging
├── stonecutter.gradle            # Version / loader branch definitions
└── settings.gradle
```

### Technical Notes

- Multi-version support is handled by [Stonecutter](https://stonecutter.kikugie.dev/); version differences live in `//? if <version> { ... //?}` conditional blocks inside the sources.
- The MTR mod itself is never modified — every feature is added through Mixin injection.
- All MTR data model access is centralized in `src/main/java/com/mtrmap/server/MtrNetwork.java`, which exposes version-neutral snapshot types to the rest of the code and hides the 3.x / 4.x API differences.

### License

Apache-2.0
