# MTR Map

[English](#english) | 中文

在 Minecraft 里查看 **Minecraft Transit Railway（MTR）** 的线路图：既可以在浏览器打开 `http://localhost:1145` 看网页地图，也可以在游戏内按 **F6** 打开地图窗口。包含车站、线路、换乘站、车厂、列车与玩家位置，支持路径查询、导航下发、行程记录与图片导出。

> 这份说明是写给玩家的。开发者请看 [GitHub 仓库](https://github.com/yingzhengxun/MTRmap) 里的 `README.md`。

## 功能亮点

### 网页地图（浏览器）

- **Xaero 世界地图底图**：底图直接用 **Xaero 的世界地图**数据（游戏内把它的地图贴图合成瓦片，再传给服务端给网页用），配色、地形细节和你在 Xaero 里看到的一模一样；线网直接叠在地形上。
- **线路图**：画布绘制线路、车站、换乘站、车厂与列车；换乘站用跑道形（胶囊）标记，范围覆盖该站所有线路的站台。
- **搜索与详情**：随时搜索车站或线路，点开看详情侧边栏（坐标、经过线路、站数、全程时间、班次间隔）。
- **路径查询**：纯前端计算的多种方案，标注换乘次数、总距离、预计时间与乘车方向（`开往 <终点站>`）。
- **我的位置**：每 2 秒刷新，位置变化自动重绘地图、更新出发点并重新查询路线；退出游戏后自动隐藏玩家卡片。
- **行程记录**：按玩家存档，可删除单条记录，一键生成**纪念票根**（PNG 下载）。
- **导航下发**：选好方案后把乘车步骤推送到游戏内，HUD 面板会显示当前行程进度。
- 夜间模式、中英文切换、导出图片（PNG / JPG / WebP，可选质量）、地图字体选择、缩放与重置视图、工具栏折叠。

### 游戏内

- **地图窗口（F6）**：以 Xaero 世界地图为底图，叠加 MTR 线网、车站、换乘站、车厂、列车与玩家；拖拽平移、滚轮缩放；左侧搜索与线路一览，右侧工具栏（路径查询 / 夜间模式 / 中英文 / 导出图片 / 车厂开关 / 缩放 / 重置 / 我的位置 / 行程记录）。网页地图与游戏内窗口功能保持一致。
- **铁路仪表板按钮**：在 MTR 的铁路仪表板上注入「交通线路图」按钮，点一下用系统浏览器打开网页地图。
- **导出图片**：整张线网导出成 PNG / JPG（最长边 4096）；纪念票根在行程记录里一键保存为 PNG（只保存，不做打印）。文件保存在 `<游戏目录>/mtrmap/`。
- **导航 HUD**：显示当前行程进度；无界面时按 `Ctrl+X` 退出导航；完成或退出导航时上传行程记录。

## 支持的版本

| Minecraft | 加载器 | 下载哪个 jar |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<版本>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<版本>-1.21.1-universal.jar` |

每个 MC 版本只发布一个 `universal` jar，同一个文件在对应的两个加载器下都能直接使用，不用区分。

## 安装

1. 装好对应版本的 Minecraft 与加载器。
2. 装 **MTR（Minecraft Transit Railway）**：3.x 与 4.x 都可以（依赖范围是通配符，不挑版本）。
3. 装 **Xaero 的世界地图（Xaero's World Map）**：**这是必装的前置**——底图就是它的地图数据（1.20.1 / 1.21.1 用 1.46.0 实测可用）。只装 Xaero 的小地图是不够的，必须是**世界地图**。
4. Fabric 用户还要装 **Fabric API**；Forge / NeoForge 不需要。
5. 把 `mtrmap-<版本>-<MC 版本>-universal.jar` 放进 `mods/` 文件夹。

服务端（包括开服的那台机器）同样需要装 MTR 与 Xaero 的世界地图，否则本模组不会加载。

## 快速上手

1. 进入一个存档 / 服务器（**本机**开服或单机，见下面「常见问题」）。
2. 浏览器打开 `http://localhost:1145`（端口被占用时以聊天栏提示的实际端口为准）。
3. 游戏内按 **F6** 打开地图窗口；按 `Ctrl+X`（在没有打开任何界面时）退出导航。

## 配置

配置文件在 `mods/mapconfig/mtrmap.json`，不存在会自动创建：

```json
{
  "showDepots": true,
  "port": 1145
}
```

| 字段 | 说明 |
| --- | --- |
| `showDepots` | 是否显示车厂 |
| `port` | 网页地图端口，默认 `1145`。被占用时会自动往后顺延到第一个可用端口，并在你进游戏时用消息提示实际端口 |

底图由模组自己生成：服务端每 tick 在玩家周围扫描**已加载**的区块（不会为了画地图强制加载或生成区块），把地表方块按原版地图配色采成像素，再按缩放级别合成 512×512 的瓦片，通过 `/api/worldmap/...` 提供给网页与游戏内窗口。所以地图覆盖范围 = 服务器加载过的区域；玩家没去过的地方是底色，走过去就会出现。缩放范围 1 像素 = 1～16 方块（`/api/worldmap/settings` 里的 `maxZoom` / `minZoom`）。

行程记录保存在 `mods/mapconfig/mtrmap_trips.json`。

### 命令

```
/mtrmap showdepots <true|false>
```

## 常见问题

**Q：浏览器打不开 `localhost:1145`？**
A：看游戏启动时聊天栏的提示——端口被占用时模组会自动顺延，提示里会给出实际端口，用那个端口访问。局域网里想让别人也能访问，需要放行该端口的入站连接。

**Q：底图只有一片底色，没有地形？**
A：底图来自 **Xaero 的世界地图**——只有你在 Xaero 那边看过 / 探索过的区域才有地形。所以：刚打开 F6 或缩得很远时会先显示「底图准备中」，稍等一下地形会慢慢长出来；如果某片区域一直是空的，用 Xaero 的世界地图飞过去看一眼，本模组的底图随后就会补上。若 F6 窗口中央提示底图不可用，先确认地图服务是否正常（见上一条）。

**Q：连远程服务器能用吗？**
A：本模组的数据采集与地图服务都跑在**服务端**，游戏内 F6 窗口也是从 `localhost:1145` 取数据的。所以在远程服务器上要用，需要把服务器的 **1145** 端口转发 / 开放到你本机，否则网页地图打不开、F6 窗口也没有数据。另外：因为底图由**客户端**用 Xaero 的地图数据合成后上传给服务端，所以网页地图的底图需要至少有一个装了 Xaero 的客户端在线浏览过那片区域。

**Q：装完进游戏报错 / 模组列表里没有？**
A：检查四件事：MC 版本与加载器是否和下载的 jar 对得上；MTR 是否已安装；**Xaero 的世界地图是否已安装**（必装前置，只装小地图不行）；Fabric 下 Fabric API 是否已安装。

**Q：游戏卡顿吗？**
A：网页地图和 F6 窗口都只做绘制与本地 HTTP 轮询，数据每 2 秒刷新一次。底图瓦片是从 Xaero 那边读一次、编码与缓存都放在后台线程，每帧最多处理两张瓦片，不会占住主线程。

## 反馈

有问题或建议请到 [GitHub Issues](https://github.com/yingzhengxun/MTRmap/issues) 反馈，附上 MC 版本、加载器版本与 `latest.log` 会更方便定位。

## 许可证

Apache-2.0

---

<a id="english"></a>

## English

View your **Minecraft Transit Railway (MTR)** network from anywhere: open `http://localhost:1145` in a browser for the web map, or press **F6** in game for the map window. Stations, lines, interchanges, depots, trains and player positions are all shown, with a route planner, in-game navigation dispatch, trip history and image export.

> This document is for players. Developers should read `README.md` in the [GitHub repository](https://github.com/yingzhengxun/MTRmap).

### Highlights

#### Web map (browser)

- **Xaero's World Map base layer**: the base layer uses **Xaero's World Map** data directly (in game its map textures are assembled into tiles, which are then pushed to the server for the web map), so colours and terrain detail look exactly like what you see in Xaero; the network is drawn straight on top of the terrain.
- **Network map**: canvas rendering of lines, stations, interchanges, depots and trains. Interchange stations use a stadium/capsule marker covering the platforms of every line serving them.
- **Search & details**: search stations or lines at any time and open a detail sidebar (coordinates, lines served, stop count, full-trip time, headway).
- **Route planner**: several client-side options with transfer counts, total distance, estimated time and ride direction (`towards <terminus>`).
- **My Location**: refreshed every 2 seconds; position changes redraw the map, update the origin and re-run the route query. The player card hides itself once you leave the game.
- **Trip history**: archived per player, individually deletable, with a one-click **souvenir ticket** (PNG download).
- **Navigation dispatch**: push the chosen itinerary into the game; the HUD panel shows current progress.
- Dark mode, Chinese/English toggle, image export (PNG / JPG / WebP with selectable quality), map font selector, zoom and view reset, collapsible toolbar.

#### In game

- **Map window (F6)**: Xaero's World Map as the base layer with the MTR network, stations, interchanges, depots, trains and players overlaid. Drag to pan, scroll to zoom; search and line list on the left, toolbar on the right (route planner / night mode / language / export / depots / zoom / reset / my location / trip records). The web map and the in-game window always offer the same features.
- **Railway dashboard button**: injects a "Traffic Map" button that opens the web map in your system browser.
- **Export**: the whole network as PNG / JPG (max edge 4096); a souvenir ticket can be saved as PNG from the trip records (save only, no printing). Files land in `<gameDir>/mtrmap/`.
- **Navigation HUD**: shows current trip progress; press `Ctrl+X` (with no screen open) to exit navigation; the trip is uploaded on completion or exit.

### Supported versions

| Minecraft | Loaders | Which jar to download |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | `mtrmap-<version>-1.20.1-universal.jar` |
| 1.21.1 | Fabric / NeoForge | `mtrmap-<version>-1.21.1-universal.jar` |

Each Minecraft version ships a single `universal` jar that works on both of its loaders — no per-loader build to pick.

### Installation

1. Install the matching Minecraft version and loader.
2. Install **MTR (Minecraft Transit Railway)**. Both 3.x and 4.x work — the dependency range is a wildcard.
3. Install **Xaero's World Map**. This is a **required** dependency — the base layer *is* its map data (verified with 1.46.0 on both 1.20.1 and 1.21.1). Xaero's Minimap alone is not enough; you need the **World Map**.
4. On Fabric you also need **Fabric API**; Forge / NeoForge do not.
5. Drop `mtrmap-<version>-<MC version>-universal.jar` into your `mods/` folder.

The server (including the machine you host on) needs MTR and Xaero's World Map too, otherwise this mod will not load.

### Quick start

1. Load a world or server (must be **local** — see the FAQ below).
2. Open `http://localhost:1145` in your browser (if the port was taken, use the one announced in chat).
3. Press **F6** in game for the map window; press `Ctrl+X` (with no screen open) to exit navigation.

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
| `port` | Web map port, defaults to `1145`. If it is taken, the mod shifts to the next free port and notifies you in chat with the actual port |

The base map is generated by the mod itself: every tick the server scans the chunks it has **already loaded** around each player (it never force-loads or generates chunks just to draw the map), samples the surface block with vanilla map colours, then assembles 512×512 tiles per zoom level and serves them over `/api/worldmap/...` to both the web map and the in-game window. So the map covers exactly what the server has loaded — unexplored areas show the plain background and fill in as you walk around. Zoom levels cover 1 pixel = 1..16 blocks (see `maxZoom` / `minZoom` in `/api/worldmap/settings`).

Trip records are stored in `mods/mapconfig/mtrmap_trips.json`.

#### Command

```
/mtrmap showdepots <true|false>
```

### FAQ

**Q: The browser can't open `localhost:1145`.**
A: Check the chat notice on world load — if the port was taken, the mod shifts to the next free port and tells you which one. To let others on your LAN in, allow inbound connections on that port.

**Q: The base layer is flat colour with no terrain.**
A: The base layer comes from **Xaero's World Map**, so only areas you have explored in Xaero have terrain. Right after opening F6 or when zoomed far out it first shows "preparing base map" and the terrain grows in shortly after; if an area stays empty, fly over it once in Xaero's World Map and this mod's base layer will fill in afterwards. If the F6 window says the base layer is unavailable, check that the map service is running first (see the previous question).

**Q: Does it work on a remote server?**
A: Data collection and the map service run **server-side**, and the F6 window also reads its data from `localhost:1145`. To use it on a remote server, forward / expose the server's **1145** port to your machine — otherwise the web map won't open and the F6 window will have no data. Also note that because the base layer is assembled **client-side** from Xaero's data and then uploaded to the server, the web map's base layer needs at least one online client with Xaero that has viewed that area.

**Q: The mod doesn't show up / the game errors on startup.**
A: Check four things: your MC version and loader match the jar you downloaded, MTR is installed, **Xaero's World Map is installed** (required; the minimap alone is not enough), and on Fabric that Fabric API is installed.

**Q: Does it hurt performance?**
A: The web map and the F6 window only draw and poll a local HTTP endpoint every 2 seconds. Base map tiles are read once from Xaero, then encoded and cached on background threads; at most two tiles are handled per frame, so the main thread is never held up.

### Feedback

Questions and suggestions are welcome in [GitHub Issues](https://github.com/yingzhengxun/MTRmap/issues). Including your MC version, loader version and `latest.log` makes it much easier to pin down.

### License

Apache-2.0
