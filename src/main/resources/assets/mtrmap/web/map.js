(function () {
	'use strict';

	const canvas = document.getElementById('mapCanvas');
	const ctx = canvas.getContext('2d');
	const statusEl = document.getElementById('status');
	const legendEl = document.getElementById('legend');
	const tooltipEl = document.getElementById('tooltip');

	// ===== 国际化 (i18n) =====
	const LANGUAGES = {
		zh: {
			loading: '正在加载...',
			loaded: '已加载 {stations} 个车站, {routes} 条线路, {depots} 个车厂, {trains} 辆列车',
			station: '车站',
			interchange: '换乘站',
			font_title: '地图字体',
			controls_toggle: '展开/折叠工具栏',
			depot: '车厂(60%透明)',
			player: '玩家',
			train: '列车',
			label_line: '线路：',
			label_destination: '开往：',
			label_speed: '速度：',
			label_consist: '编组：',
			label_passengers: '乘客：',
			label_players: '玩家：',
			label_status: '状态：',
			status_running: '运行中',
			status_depot: '车厂停泊',
			backend_error: '后端错误: ',
			unknown_error: '未知错误',
			load_failed: '加载地图数据失败: ',
			zoom_in: '放大',
			zoom_out: '缩小',
			reset_view: '重置视图',
			dark_mode: '夜间模式',
			light_mode: '日间模式',
			btn_lang: 'EN',
			btn_lang_title: 'English',
			page_title: 'MTR 地图',
			export_btn: '↓',
			export_btn_title: '导出图片',
			export_title: '导出图片',
			export_format: '选择格式:',
			export_quality: '质量:',
			export_cancel: '取消',
			export_downloading: '导出中...',
			depot_toggle: '显示/隐藏车厂及其出入库线路',
			route_btn_title: '路径查询',
			route_panel_title: '路径查询',
			route_close: '关闭',
			route_pick_start: '请选择起点',
			route_pick_end: '请选择终点',
			route_hint_both: '起点：{start}　终点：{end}',
			route_search: '立即查询',
			route_clear: '清除',
			route_tab_shortest: '最短',
			route_tab_fastest: '最省时',
			route_tab_fewest: '最少换乘',
			route_distance: '总距离',
			route_time: '预计时间',
			route_transfers: '换乘次数',
			route_minutes: '{n} 分钟',
			route_ride_stations: '乘坐 {n} 站',
			route_towards: '开往 {station}',
			route_transfer_hint: '在 {station} 换乘 → {line}',
			route_realtime: '实时信息：{n} 分钟后车辆到站',
			route_no_train: '暂无列车信息',
			route_start_tag: '起点',
			route_end_tag: '终点',
			route_unreachable: '无法到达',
			route_same_station: '起点与终点相同',
			route_walk: '步行 {distance}',
			route_walk_tag: '步行换乘',
			// 我的位置 / 步行方位
			my_location: '📍 我的位置',
			my_location_title: '以我当前位置为起点',
			route_not_in_game: '您未在游戏中',
			route_my_no_station: '附近没有车站',
			route_from_location: '从我的位置往 {dir}步行 {distance} 到 {station}',
			route_walk_towards: '往{dir}方向',
			route_nav_sent: '导航已同步到游戏',
			dir_n: '北',
			dir_ne: '东北',
			dir_e: '东',
			dir_se: '东南',
			dir_s: '南',
			dir_sw: '西南',
			dir_w: '西',
			dir_nw: '西北',
			// 行程记录 / 票根
			user_trips: '行程记录',
			trips_title: '行程记录',
			trips_empty: '还没有行程记录',
			trips_close: '关闭',
			trip_status_completed: '已完成',
			trip_status_stopped: '已停止',
			trip_delete: '删除',
			trip_ticket: '打印纪念票根',
			trip_delete_confirm: '确定要删除这条行程记录吗？删除后无法恢复。',
			confirm_title: '确认',
			confirm_ok: '确认',
			confirm_cancel: '取消',
			ticket_title: '纪念票根',
			ticket_save: '直接保存',
			ticket_print: '打开打印页面',
			ticket_cancel: '取消',
			ticket_heading: 'MTR 纪念票根',
			ticket_from: '起点',
			ticket_to: '终点',
			ticket_date: '日期',
			ticket_distance: '里程',
			ticket_time: '用时',
			ticket_transfers: '换乘',
			ticket_footer: 'MTR Map · 旅途纪念',
			// 搜索 / 侧边栏
			search_type_station: '车站',
			search_type_route: '线路',
			search_placeholder_station: '搜索车站',
			search_placeholder_route: '搜索线路',
			search_no_result: '无匹配结果',
			search_stop_count: '{n} 站',
			detail_coord: '坐标',
			detail_lines: '经过线路',
			detail_no_lines: '暂无线路经过',
			detail_headway: '约 {n} 分钟一班',
			detail_headway_unknown: '班次间隔未知',
			detail_full_trip: '坐完全程约需 {n} 分钟',
			detail_lang_title: '切换中英文',
		},
		en: {
			loading: 'Loading...',
			loaded: 'Loaded {stations} stations, {routes} routes, {depots} depots, {trains} trains',
			station: 'Station',
			interchange: 'Interchange',
			font_title: 'Map font',
			controls_toggle: 'Collapse/expand toolbar',
			depot: 'Depot (60% opacity)',
			player: 'Player',
			train: 'Train',
			label_line: 'Line: ',
			label_destination: 'Destination: ',
			label_speed: 'Speed: ',
			label_consist: 'Consist: ',
			label_passengers: 'Passengers: ',
			label_players: 'Players: ',
			label_status: 'Status: ',
			status_running: 'In Service',
			status_depot: 'Depot',
			backend_error: 'Backend Error: ',
			unknown_error: 'Unknown',
			load_failed: 'Failed to load map data: ',
			zoom_in: 'Zoom In',
			zoom_out: 'Zoom Out',
			reset_view: 'Reset View',
			dark_mode: 'Dark Mode',
			light_mode: 'Light Mode',
			btn_lang: '中',
			btn_lang_title: '中文',
			page_title: 'MTR Map',
			export_btn: '↓',
			export_btn_title: 'Export Image',
			export_title: 'Export Image',
			export_format: 'Format:',
			export_quality: 'Quality:',
			export_cancel: 'Cancel',
			export_downloading: 'Exporting...',
			depot_toggle: 'Show/hide depots and depot links',
			route_btn_title: 'Route Planner',
			route_panel_title: 'Route Planner',
			route_close: 'Close',
			route_pick_start: 'Select the start station',
			route_pick_end: 'Select the end station',
			route_hint_both: 'From: {start}  To: {end}',
			route_search: 'Search',
			route_clear: 'Clear',
			route_tab_shortest: 'Shortest',
			route_tab_fastest: 'Fastest',
			route_tab_fewest: 'Fewest Transfers',
			route_distance: 'Distance',
			route_time: 'Est. Time',
			route_transfers: 'Transfers',
			route_minutes: '{n} min',
			route_ride_stations: 'Ride {n} stop(s)',
			route_towards: 'To {station}',
			route_transfer_hint: 'Transfer at {station} → {line}',
			route_realtime: 'Live: next train in {n} min',
			route_no_train: 'No train info',
			route_start_tag: 'Start',
			route_end_tag: 'End',
			route_unreachable: 'Unreachable',
			route_same_station: 'Start and end are the same',
			route_walk: 'Walk {distance}',
			route_walk_tag: 'Walk transfer',
			my_location: '📍 My Location',
			my_location_title: 'Use my current position as origin',
			route_not_in_game: 'You are not in game',
			route_my_no_station: 'No station nearby',
			route_from_location: 'Walk {distance} {dir} from my location to {station}',
			route_walk_towards: 'Head {dir}',
			route_nav_sent: 'Navigation synced to game',
			dir_n: 'north',
			dir_ne: 'northeast',
			dir_e: 'east',
			dir_se: 'southeast',
			dir_s: 'south',
			dir_sw: 'southwest',
			dir_w: 'west',
			dir_nw: 'northwest',
			user_trips: 'Trip Log',
			trips_title: 'Trip Log',
			trips_empty: 'No trips yet',
			trips_close: 'Close',
			trip_status_completed: 'Completed',
			trip_status_stopped: 'Stopped',
			trip_delete: 'Delete',
			trip_ticket: 'Print Souvenir Ticket',
			trip_delete_confirm: 'Delete this trip record? This cannot be undone.',
			confirm_title: 'Confirm',
			confirm_ok: 'Confirm',
			confirm_cancel: 'Cancel',
			ticket_title: 'Souvenir Ticket',
			ticket_save: 'Save',
			ticket_print: 'Open Print Page',
			ticket_cancel: 'Cancel',
			ticket_heading: 'MTR SOUVENIR TICKET',
			ticket_from: 'FROM',
			ticket_to: 'TO',
			ticket_date: 'DATE',
			ticket_distance: 'DISTANCE',
			ticket_time: 'DURATION',
			ticket_transfers: 'TRANSFERS',
			ticket_footer: 'MTR Map · A journey to remember',
			search_type_station: 'Station',
			search_type_route: 'Route',
			search_placeholder_station: 'Search station',
			search_placeholder_route: 'Search route',
			search_no_result: 'No matches',
			search_stop_count: '{n} stops',
			detail_coord: 'Coordinates',
			detail_lines: 'Lines',
			detail_no_lines: 'No lines',
			detail_headway: 'Every ~{n} min',
			detail_headway_unknown: 'Headway unknown',
			detail_full_trip: 'Full journey ~{n} min',
			detail_lang_title: 'Switch language',
		}
	};

	// ===== 可选字体 =====
	// 只作用于地图画布上的文字（站名 / 车厂名 / 玩家名 / 选点标签），界面本身不跟着变。
	const FONTS = [
		{ key: 'yahei', zh: '微软雅黑', en: 'Microsoft YaHei', family: '"Microsoft YaHei", "Segoe UI", sans-serif' },
		{ key: 'simhei', zh: '黑体', en: 'SimHei', family: '"SimHei", "Microsoft YaHei", sans-serif' },
		{ key: 'simsun', zh: '宋体', en: 'SimSun', family: '"SimSun", "Microsoft YaHei", serif' },
		{ key: 'kaiti', zh: '楷体', en: 'KaiTi', family: '"KaiTi", "Microsoft YaHei", serif' },
		{ key: 'dengxian', zh: '等线', en: 'DengXian', family: '"DengXian", "Microsoft YaHei", sans-serif' },
		{ key: 'arial', zh: 'Arial', en: 'Arial', family: 'Arial, Helvetica, sans-serif' },
		{ key: 'times', zh: 'Times New Roman', en: 'Times New Roman', family: '"Times New Roman", Times, serif' },
		{ key: 'consolas', zh: 'Consolas', en: 'Consolas', family: 'Consolas, "Courier New", monospace' }
	];

	// ===== 状态 =====
	let currentLang = 'zh';
	let currentFont = FONTS[0].key;
	let isDarkMode = true;
	let scale = 1;
	let offsetX = 0;
	let offsetY = 0;
	let viewInitialized = false;
	let lastClientX = 0;
	let lastClientY = 0;
	let mapData = { stations: [], routes: [], depots: [], showDepots: true };
	// 车厂显示开关。null 表示尚未决定，首次拿到数据时采用服务端配置作为默认值；
	// 之后由用户按钮控制（不能直接用 mapData.showDepots，因为数据每秒都会刷新覆盖）。
	let showDepots = null;
	let players = [];
	let stationMap = {};
	let avatarCache = {};
	// 自研世界地图底图：服务端渲染的瓦片参数与图片缓存
	let worldMapSettings = null;      // {tileSize, maxZoom, minZoom, ...}，会话内固定；未拿到时为 null
	const worldMapTiles = {};         // "z/tx_ty" -> Image（加载中或已加载）
	const worldMapFailed = {};        // "z/tx_ty" -> 失败时间戳（404 表示该区域尚未采样）
	let isDragging = false;
	let lastMouseX = 0;
	let lastMouseY = 0;
	let mouseDownX = 0;
	let mouseDownY = 0;

	// 路径查询状态
	let routeMode = false;
	let routeStartId = null;
	let routeEndId = null;
	let routeResult = null;
	let activeRouteTab = 0;
	let legExpandState = {};
	let uiColors = { start: '#2ecc71', end: '#ff5555' };
	// 我的位置 / 游戏内导航同步
	let myPlayer = null;       // {uuid, name, x, z}：网页识别到的游戏内玩家
	let myLocation = null;     // {x, z}：我在地图上的实际位置
	let startWalkInfo = null;  // {dist, dirKey}：从我的位置步行到起点站的指引
	let routeFromMyLocation = false; // 起点是否由「我的位置」设定
	let lastMyPos = null;      // 上一次刷新到的坐标，用来判断有没有走动
	let navStartedAt = 0;      // 当前导航的起始时间（同一条路线保持不变）
	let lastNavRev = '';       // 最近一次下发的导航 rev，用来判断路线有没有换
	// 行程记录 / 票根
	let currentTrips = [];
	let pendingConfirm = null; // 删除确认框的回调
	let currentTicket = null;  // 当前展示的票根 {trip, dataUrl}
	// 搜索 / 右侧详情侧边栏
	let highlight = null;       // {type:'station'|'route', id}：地图上高亮的目标
	let detailState = null;     // {type, id}：侧边栏正在展示的对象
	let detailLang = 'zh';      // 侧边栏站名用中文还是英文（没有译名时回退本名）
	let searchMatches = [];     // 当前候选词
	let searchActiveIndex = -1; // 键盘上下键选中的候选词
	let lineListSignature = ''; // 线路一览的签名，用来判断要不要重绘

	// ===== i18n 工具 =====
	function t(key, replacements) {
		let text = (LANGUAGES[currentLang] && LANGUAGES[currentLang][key]) ||
			(LANGUAGES['zh'] && LANGUAGES['zh'][key]) || key;
		if (replacements) {
			for (const [k, v] of Object.entries(replacements)) {
				text = text.replace('{' + k + '}', v);
			}
		}
		return text;
	}

	// ===== 画布字体 =====
	// 把当前选中的字体拼成 ctx.font 能用的字符串
	function fontFamily() {
		const font = FONTS.find(f => f.key === currentFont);
		return font ? font.family : FONTS[0].family;
	}

	function canvasFont(px, weight) {
		return (weight ? weight + ' ' : '') + px.toFixed(1) + 'px ' + fontFamily();
	}

	// ===== 主题 / 语言切换 =====
	const controlsEl = document.getElementById('controls');
	const controlsToggle = document.getElementById('controlsToggle');
	const fontSelect = document.getElementById('fontSelect');
	const themeBtn = document.getElementById('themeToggle');
	const langBtn = document.getElementById('langToggle');
	const exportBtn = document.getElementById('exportBtn');
	const depotBtn = document.getElementById('depotBtn');
	const exportDialog = document.getElementById('exportDialog');
	const exportTitle = document.getElementById('exportTitle');
	const exportCancel = document.getElementById('exportCancel');
	const exportOverlay = document.getElementById('exportOverlay');
	const qualitySlider = document.getElementById('qualitySlider');
	const qualityDisplay = document.getElementById('qualityDisplay');
	const qualityRow = document.getElementById('exportQualityRow');
	const routeBtn = document.getElementById('routeBtn');
	const routePanel = document.getElementById('routePanel');
	const routePanelTitle = document.getElementById('routePanelTitle');
	const routeCloseBtn = document.getElementById('routeCloseBtn');
	const routeHintEl = document.getElementById('routeHint');
	const routeTabsEl = document.getElementById('routeTabs');
	const routeDetailEl = document.getElementById('routeDetail');
	const routeSearchBtn = document.getElementById('routeSearchBtn');
	const routeClearBtn = document.getElementById('routeClearBtn');
	const routeMyLocationBtn = document.getElementById('routeMyLocationBtn');
	// 用户卡片 / 行程记录 / 票根
	const userCard = document.getElementById('userCard');
	const userCardMain = document.getElementById('userCardMain');
	const userAvatar = document.getElementById('userAvatar');
	const userNameEl = document.getElementById('userName');
	const userMenu = document.getElementById('userMenu');
	const userTripsBtn = document.getElementById('userTripsBtn');
	const tripsDialog = document.getElementById('tripsDialog');
	const tripsOverlay = document.getElementById('tripsOverlay');
	const tripsTitle = document.getElementById('tripsTitle');
	const tripsList = document.getElementById('tripsList');
	const tripsClose = document.getElementById('tripsClose');
	const confirmDialog = document.getElementById('confirmDialog');
	const confirmOverlay = document.getElementById('confirmOverlay');
	const confirmTitle = document.getElementById('confirmTitle');
	const confirmMessage = document.getElementById('confirmMessage');
	const confirmOk = document.getElementById('confirmOk');
	const confirmCancel = document.getElementById('confirmCancel');
	const ticketDialog = document.getElementById('ticketDialog');
	const ticketOverlay = document.getElementById('ticketOverlay');
	const ticketTitle = document.getElementById('ticketTitle');
	const ticketPreview = document.getElementById('ticketPreview');
	const ticketSave = document.getElementById('ticketSave');
	const ticketPrint = document.getElementById('ticketPrint');
	const ticketCancel = document.getElementById('ticketCancel');
	// 搜索 / 详情侧边栏
	const searchBox = document.getElementById('searchBox');
	const searchType = document.getElementById('searchType');
	const searchInput = document.getElementById('searchInput');
	const searchResults = document.getElementById('searchResults');
	const detailPanel = document.getElementById('detailPanel');
	const detailTitle = document.getElementById('detailTitle');
	const detailBody = document.getElementById('detailBody');
	const detailLangBtn = document.getElementById('detailLangBtn');
	const detailCloseBtn = document.getElementById('detailCloseBtn');
	const lineListEl = document.getElementById('lineList');

	function applyTheme() {
		document.body.classList.toggle('light-mode', !isDarkMode);
		themeBtn.textContent = isDarkMode ? '☾' : '☀';
		themeBtn.title = t(isDarkMode ? 'dark_mode' : 'light_mode');
		refreshUiColors();
		render();
	}

	function applyLang() {
		currentLang = currentLang === 'zh' ? 'en' : 'zh';
		document.documentElement.lang = currentLang === 'zh' ? 'zh-CN' : 'en';
		document.title = t('page_title');
		langBtn.textContent = t('btn_lang');
		langBtn.title = t('btn_lang_title');
		themeBtn.title = t(isDarkMode ? 'dark_mode' : 'light_mode');
		document.getElementById('zoomIn').title = t('zoom_in');
		document.getElementById('zoomOut').title = t('zoom_out');
		document.getElementById('reset').title = t('reset_view');
		exportBtn.title = t('export_btn_title');
		controlsToggle.title = t('controls_toggle');
		fontSelect.title = t('font_title');
		updateDepotBtn();
		updateLegend();
		updateStatus();
		// 字体下拉框的选项名随语言切换
		fillFontSelect();
		// 已打开的路径查询面板文案同步刷新
		updateRoutePanel();
		// 搜索框文案与详情侧边栏（侧边栏语言跟随界面语言）
		updateSearchUiText();
		searchResults.style.display = 'none';
		detailLang = currentLang;
		if (detailState) renderDetailPanel();
		render();
		updateTooltip(lastClientX, lastClientY);
	}

	themeBtn.addEventListener('click', () => {
		isDarkMode = !isDarkMode;
		applyTheme();
	});

	langBtn.addEventListener('click', () => {
		applyLang();
	});

	// ===== 工具栏展开 / 折叠 =====
	controlsToggle.addEventListener('click', () => {
		const collapsed = controlsEl.classList.toggle('collapsed');
		controlsToggle.textContent = collapsed ? '▸' : '▾';
	});

	// ===== 地图字体选择 =====
	/** 按当前语言填充字体下拉框（选项名用各语言自己的叫法） */
	function fillFontSelect() {
		fontSelect.innerHTML = '';
		FONTS.forEach(f => {
			const opt = document.createElement('option');
			opt.value = f.key;
			opt.textContent = currentLang === 'en' ? f.en : f.zh;
			fontSelect.appendChild(opt);
		});
		fontSelect.value = currentFont;
	}

	fontSelect.addEventListener('change', () => {
		currentFont = fontSelect.value;
		render();
	});

	// ===== 车厂显示开关 =====
	/** 同步车厂按钮的提示文字与"关闭"外观 */
	function updateDepotBtn() {
		depotBtn.title = t('depot_toggle');
		// showDepots 为 null（数据还没到时）按开启处理，避免按钮先闪一下"关闭"态
		depotBtn.classList.toggle('off', showDepots === false);
	}

	depotBtn.addEventListener('click', () => {
		showDepots = !showDepots;
		updateDepotBtn();
		render();
	});

	// ===== 导出图片 =====
	function calculateMapBounds() {
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
		let hasData = false;
		if (mapData.stations) {
			mapData.stations.forEach(s => {
				if (s.x !== undefined) {
					minX = Math.min(minX, s.x); maxX = Math.max(maxX, s.x);
					minZ = Math.min(minZ, s.z); maxZ = Math.max(maxZ, s.z);
					hasData = true;
				}
			});
		}
		// 导出范围只统计当前实际显示的内容（车厂被隐藏时不参与计算）
		if (mapData.depots && showDepots) {
			mapData.depots.forEach(d => {
				if (d.x1 !== undefined) {
					minX = Math.min(minX, d.x1, d.x2); maxX = Math.max(maxX, d.x1, d.x2);
					minZ = Math.min(minZ, d.z1, d.z2); maxZ = Math.max(maxZ, d.z1, d.z2);
					hasData = true;
				}
			});
		}
		if (mapData.routes) {
			mapData.routes.forEach(r => {
				if (r.paths) {
					r.paths.forEach(seg => {
						if (seg) seg.forEach(pt => {
							minX = Math.min(minX, pt.x); maxX = Math.max(maxX, pt.x);
							minZ = Math.min(minZ, pt.z); maxZ = Math.max(maxZ, pt.z);
							hasData = true;
						});
					});
				}
			});
		}
		if (mapData.trains) {
			mapData.trains.forEach(t => {
				if (t.x !== undefined) {
					minX = Math.min(minX, t.x); maxX = Math.max(maxX, t.x);
					minZ = Math.min(minZ, t.z); maxZ = Math.max(maxZ, t.z);
					hasData = true;
				}
			});
		}
		if (players) {
			players.forEach(p => {
				minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
				minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
				hasData = true;
			});
		}
		return hasData ? { minX, minZ, maxX, maxZ } : null;
	}

	function exportImage(format) {
		const bounds = calculateMapBounds();
		if (!bounds) return;
		const quality = qualitySlider.value / 100;
		const padding = 200;
		const mapW = bounds.maxX - bounds.minX || 100;
		const mapH = bounds.maxZ - bounds.minZ || 100;

		// 计算导出画布大小（最长边上限 4096）
		const maxDim = 4096;
		let expW, expH;
		if (mapW > mapH) {
			expW = maxDim;
			expH = Math.max(1, Math.round((mapH / mapW) * maxDim));
		} else {
			expH = maxDim;
			expW = Math.max(1, Math.round((mapW / mapH) * maxDim));
		}

		// 计算适配 scale 和偏移
		const expScale = Math.min((expW - padding * 2) / mapW, (expH - padding * 2) / mapH);
		const expOffX = (expW - mapW * expScale) / 2 - bounds.minX * expScale;
		const expOffY = (expH - mapH * expScale) / 2 - bounds.minZ * expScale;

		// 保存当前视图状态
		const saved = { scale, offsetX, offsetY, width: canvas.width, height: canvas.height };

		// 设置导出视图到主 canvas
		scale = expScale;
		offsetX = expOffX;
		offsetY = expOffY;
		canvas.width = expW;
		canvas.height = expH;

		// 导出时强制使用当前主题（跟随 isDarkMode）
		render();

		// 导出为 blob
		const mimeTypes = { png: 'image/png', jpeg: 'image/jpeg', webp: 'image/webp' };
		const exts = { png: 'png', jpeg: 'jpg', webp: 'webp' };
		canvas.toBlob(function(blob) {
			if (blob) {
				const url = URL.createObjectURL(blob);
				const a = document.createElement('a');
				a.href = url;
				a.download = 'mtrmap_' + new Date().toISOString().slice(0, 10) + '.' + exts[format];
				document.body.appendChild(a);
				a.click();
				document.body.removeChild(a);
				URL.revokeObjectURL(url);
			}
			// 恢复视图
			scale = saved.scale;
			offsetX = saved.offsetX;
			offsetY = saved.offsetY;
			canvas.width = saved.width;
			canvas.height = saved.height;
			render();
			exportDialog.style.display = 'none';
		}, mimeTypes[format], format === 'png' ? undefined : quality);
	}

	exportBtn.addEventListener('click', () => {
		exportTitle.textContent = t('export_title');
		qualitySlider.value = 92;
		qualityDisplay.textContent = '92%';
		qualityRow.style.display = 'flex';
		exportDialog.style.display = 'flex';
	});

	exportOverlay.addEventListener('click', () => {
		exportDialog.style.display = 'none';
	});

	exportCancel.addEventListener('click', () => {
		exportDialog.style.display = 'none';
	});

	qualitySlider.addEventListener('input', () => {
		qualityDisplay.textContent = qualitySlider.value + '%';
	});

	document.querySelectorAll('.export-fmt-btn').forEach(btn => {
		btn.addEventListener('click', () => {
			const format = btn.getAttribute('data-format');
			// JPEG/WebP 允许调质量, PNG 隐藏质量行
			qualityRow.style.display = (format === 'jpeg' || format === 'webp') ? 'flex' : 'none';
			exportTitle.textContent = t('export_downloading');
			exportImage(format);
		});
	});

	// ===== 图例 =====
	function updateLegend() {
		legendEl.innerHTML =
			'<div class="legend-item"><span class="dot station-dot"></span>' + t('station') + '</div>' +
			'<div class="legend-item"><span class="dot interchange-dot"></span>' + t('interchange') + '</div>' +
			'<div class="legend-item"><span class="dot depot-dot"></span>' + t('depot') + '</div>' +
			'<div class="legend-item"><span class="dot player-dot"></span>' + t('player') + '</div>' +
			'<div class="legend-item"><span class="dot train-dot"></span>' + t('train') + '</div>';
	}

	// ===== 状态栏 =====
	function updateStatus() {
		if (mapData.error) {
			statusEl.textContent = t('backend_error') + (mapData.message || t('unknown_error'));
			return;
		}
		const count = mapData.stations ? mapData.stations.length : 0;
		const routeCount = mapData.routes ? mapData.routes.length : 0;
		const depotCount = mapData.depots ? mapData.depots.length : 0;
		const trainCount = mapData.trains ? mapData.trains.length : 0;
		statusEl.textContent = t('loaded', {
			stations: count,
			routes: routeCount,
			depots: depotCount,
			trains: trainCount
		});
	}

	// ===== 工具函数 =====
	function splitName(name) {
		const parts = String(name || '').split('|');
		return {
			main: parts[0].trim(),
			trans: parts.length > 1 ? parts.slice(1).join('|').trim() : ''
		};
	}

	function intToRgba(n, alphaOverride) {
		n = n >>> 0;
		const a = alphaOverride !== undefined ? alphaOverride : ((n >> 24) & 0xFF) / 255;
		const r = (n >> 16) & 0xFF;
		const g = (n >> 8) & 0xFF;
		const b = n & 0xFF;
		return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
	}

	function worldToCanvas(x, z) {
		return { x: x * scale + offsetX, y: z * scale + offsetY };
	}

	function canvasToWorld(cx, cy) {
		return { x: (cx - offsetX) / scale, z: (cy - offsetY) / scale };
	}

	function fitView() {
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
		const points = [];
		if (mapData.stations) {
			mapData.stations.forEach(s => {
				if (s.x !== undefined) points.push({ x: s.x, z: s.z });
			});
		}
		if (mapData.depots) {
			mapData.depots.forEach(d => {
				if (d.centerX !== undefined) points.push({ x: d.centerX, z: d.centerZ });
			});
		}
		if (players) {
			players.forEach(p => { points.push({ x: p.x, z: p.z }); });
		}
		if (points.length === 0) return;
		points.forEach(p => {
			minX = Math.min(minX, p.x);
			minZ = Math.min(minZ, p.z);
			maxX = Math.max(maxX, p.x);
			maxZ = Math.max(maxZ, p.z);
		});
		const w = maxX - minX || 100;
		const h = maxZ - minZ || 100;
		const padding = 80;
		scale = Math.min((canvas.width - padding * 2) / w, (canvas.height - padding * 2) / h);
		if (scale <= 0 || !isFinite(scale)) scale = 1;
		const centerX = (minX + maxX) / 2;
		const centerZ = (minZ + maxZ) / 2;
		offsetX = canvas.width / 2 - centerX * scale;
		offsetY = canvas.height / 2 - centerZ * scale;
		viewInitialized = true;
	}

	// ===== 数据加载 =====
	async function loadMapData() {
		try {
			const resp = await fetch('/api/data');
			mapData = await resp.json();
			// 首次拿到数据时，用服务端配置作为车厂开关的初始值
			if (showDepots === null) {
				showDepots = mapData.showDepots !== false;
				updateDepotBtn();
			}
			stationMap = {};
			if (mapData.stations) {
				mapData.stations.forEach(s => { stationMap[s.id] = s; });
			}
			renderLineList();
			updateStatus();
			if (!viewInitialized) fitView();
		} catch (e) {
			statusEl.textContent = t('load_failed') + e.message;
		}
	}

	async function loadPlayers() {
		try {
			const resp = await fetch('/api/players');
			players = await resp.json() || [];
			players.forEach(p => {
				if (p.uuid && !avatarCache[p.uuid]) {
					const img = new Image();
					img.src = '/avatar/' + p.uuid + '.png';
					img.onload = () => { render(); };
					img.onerror = () => { avatarCache[p.uuid] = null; };
					avatarCache[p.uuid] = img;
				}
			});
		} catch (e) { /* silent */ }
	}

	// 获取自研世界地图的瓦片参数。服务器可能尚未就绪，失败时每 5 秒重试一次
	function loadWorldMapSettings() {
		fetch('/api/worldmap/settings')
			.then(resp => {
				if (!resp.ok) throw new Error('HTTP ' + resp.status);
				return resp.json();
			})
			.then(s => {
				if (!s || !s.tileSize || s.maxZoom === undefined) throw new Error('bad settings');
				worldMapSettings = s;
				render();
			})
			.catch(() => { setTimeout(loadWorldMapSettings, 5000); });
	}

	// ===== 绘制 =====
	function render() {
		ctx.clearRect(0, 0, canvas.width, canvas.height);
		drawBackground();
		// 自研世界地图底图：画在网格之上、列车网络之下；出错也不能影响后续绘制
		try { drawWorldMapBase(); } catch (e) { /* ignore */ }
		drawDepots();
		// 路径查询模式下线路改为「相邻车站直线」的示意图
		if (routeMode) {
			drawSchematicRoutes();
		} else {
			drawRoutes();
		}
		// 高亮画在车站标记之前：线路光晕不会糊住站名，车站金环则正好衬在站点下方
		drawHighlight();
		drawStations();
		if (!routeMode) drawTrains();
		drawPlayers();
		if (routeMode) drawRouteSelection();
		if (routeMode) drawWalkNavigation();
	}

	function drawBackground() {
		const gridColor = isDarkMode ? 'rgba(60, 60, 100, 0.2)' : 'rgba(0, 0, 0, 0.08)';
		ctx.strokeStyle = gridColor;
		ctx.lineWidth = 1;
		const gridSize = 64;
		const topLeft = canvasToWorld(0, 0);
		const bottomRight = canvasToWorld(canvas.width, canvas.height);
		const startX = Math.floor(topLeft.x / gridSize) * gridSize;
		const startZ = Math.floor(topLeft.z / gridSize) * gridSize;
		ctx.beginPath();
		for (let x = startX; x <= bottomRight.x; x += gridSize) {
			const p = worldToCanvas(x, 0);
			ctx.moveTo(p.x, 0);
			ctx.lineTo(p.x, canvas.height);
		}
		for (let z = startZ; z <= bottomRight.z; z += gridSize) {
			const p = worldToCanvas(0, z);
			ctx.moveTo(0, p.y);
			ctx.lineTo(canvas.width, p.y);
		}
		ctx.stroke();
	}

	// 绘制自研世界地图底图。瓦片坐标约定与服务端一致：
	// 在层级 z，1 像素 = 2^(maxZoom - z) 个方块，故一块瓦片覆盖 tileSize * 2^(maxZoom-z) 个方块。
	function drawWorldMapBase() {
		if (!worldMapSettings) return;
		if (!canvas.width || !canvas.height) return;
		if (!(scale > 0)) return;
		const tileSize = worldMapSettings.tileSize;
		const maxZoom = worldMapSettings.maxZoom;
		const minZoom = worldMapSettings.minZoom;
		// 由当前缩放反推最合适的层级（scale 越大用越细的层级）
		let zoom = Math.floor(maxZoom + Math.log2(scale));
		if (zoom < minZoom) zoom = minZoom;
		if (zoom > maxZoom) zoom = maxZoom;
		const blocksPerTile = tileSize * Math.pow(2, maxZoom - zoom);
		// 视野覆盖的世界范围 -> 瓦片索引区间（左上索引可能为负，必须用 Math.floor）
		const topLeft = canvasToWorld(0, 0);
		const bottomRight = canvasToWorld(canvas.width, canvas.height);
		const minTx = Math.floor(topLeft.x / blocksPerTile);
		const maxTx = Math.floor(bottomRight.x / blocksPerTile);
		const minTy = Math.floor(topLeft.z / blocksPerTile);
		const maxTy = Math.floor(bottomRight.z / blocksPerTile);
		const now = Date.now();
		let newImages = 0;   // 本帧新建的图片数（限制并发请求）
		let drawn = 0;       // 本帧已绘制的瓦片数
		for (let ty = minTy; ty <= maxTy; ty++) {
			for (let tx = minTx; tx <= maxTx; tx++) {
				if (newImages >= 8 || drawn >= 100) return;
				const key = zoom + '/' + tx + '_' + ty;
				const entry = worldMapTiles[key];
				if (!entry) {
					// 404 过的瓦片 10 秒内不再请求，避免每帧狂发请求
					const failedAt = worldMapFailed[key];
					if (failedAt && now - failedAt < 10000) continue;
					const img = new Image();
					img.onload = () => { render(); };
					img.onerror = () => {
						// 该区域尚未采样（404），10 秒后再试一次
						worldMapFailed[key] = Date.now();
						delete worldMapTiles[key];
						setTimeout(() => { render(); }, 10000);
					};
					img.src = '/api/worldmap/' + zoom + '/' + tx + '_' + ty + '.png';
					worldMapTiles[key] = img;
					newImages++;
					continue;
				}
				if (!entry.complete || entry.naturalWidth === 0) continue; // 尚未加载完成
				const p = worldToCanvas(tx * blocksPerTile, ty * blocksPerTile);
				const size = blocksPerTile * scale;
				// +1 像素，避免相邻瓦片之间出现 1px 缝隙
				ctx.drawImage(entry, p.x, p.y, size + 1, size + 1);
				drawn++;
			}
		}
	}

	function drawRoutes() {
		if (!mapData.routes) return;
		mapData.routes.forEach(route => {
			if (!route.stations || route.stations.length < 2) return;
			ctx.strokeStyle = intToRgba(route.color, 0.9);
			ctx.lineWidth = Math.max(2, 4 * Math.sqrt(scale));
			ctx.lineCap = 'round';
			ctx.lineJoin = 'round';
			const stations = route.stations;
			const paths = route.paths || [];
			for (let i = 0; i < stations.length - 1; i++) {
				const seg = paths[i];
				ctx.beginPath();
				let started = false;
				if (seg && seg.length >= 2) {
					seg.forEach(pt => {
						const p = worldToCanvas(pt.x, pt.z);
						if (!started) { ctx.moveTo(p.x, p.y); started = true; }
						else { ctx.lineTo(p.x, p.y); }
					});
				} else {
					const a = stationMap[stations[i]];
					const b = stationMap[stations[i + 1]];
					if (!a || !b) continue;
					const pa = worldToCanvas(a.x, a.z);
					const pb = worldToCanvas(b.x, b.z);
					ctx.moveTo(pa.x, pa.y);
					ctx.lineTo(pb.x, pb.y);
				}
				ctx.stroke();
			}
		});
	}

	// 换乘站：被 2 条及以上线路经过的车站（由服务端统计后随车站数据下发）
	function isInterchange(st) {
		return !!st && st.lines >= 2;
	}

	// 跑道形（胶囊）路径：中心 (cx, cy)、半径 r、两端圆心到中心距离 half；
	// vertical 为 true 时竖着画（南北走向的换乘站）
	function stadiumPath(cx, cy, r, half, vertical) {
		ctx.beginPath();
		if (vertical) {
			ctx.moveTo(cx - r, cy - half);
			ctx.lineTo(cx - r, cy + half);
			ctx.arc(cx, cy + half, r, Math.PI, 0, true);
			ctx.lineTo(cx + r, cy - half);
			ctx.arc(cx, cy - half, r, 0, Math.PI, true);
		} else {
			ctx.moveTo(cx - half, cy - r);
			ctx.lineTo(cx + half, cy - r);
			ctx.arc(cx + half, cy, r, -Math.PI / 2, Math.PI / 2);
			ctx.lineTo(cx - half, cy + r);
			ctx.arc(cx - half, cy, r, Math.PI / 2, Math.PI * 1.5);
		}
		ctx.closePath();
	}

	// 换乘站标记几何：以该站「全部站台中心的包围盒」为范围，
	// 这样标记能盖住经过该站的所有线路；站台沿南北排开时包围盒更高，标记自动竖过来。
	// minThick 是标记短边的最小值（普通车站直径），保证再小的站也是个清晰的胶囊。
	// 返回 null 表示没有包围盒（退回普通圆点）。
	function interchangeCapsule(st, minThick) {
		if (typeof st.bx1 !== 'number' || typeof st.bx2 !== 'number') return null;
		const p1 = worldToCanvas(st.bx1, st.bz1);
		const p2 = worldToCanvas(st.bx2, st.bz2);
		const cx = (p1.x + p2.x) / 2;
		const cy = (p1.y + p2.y) / 2;
		const w = Math.abs(p2.x - p1.x);
		const h = Math.abs(p2.y - p1.y);
		const vertical = h > w;
		// 短边要盖住另一轴的站台分布，同时不小于普通车站直径
		const r = Math.max(minThick / 2, (vertical ? w : h) / 2);
		const half = Math.max((vertical ? h : w) / 2 - r, r * 0.8);
		return { cx: cx, cy: cy, r: r, half: half, vertical: vertical };
	}

	function drawStations() {
		if (!mapData.stations) return;
		// 路径查询模式下适当放大车站，便于点击
		const radius = routeMode ? Math.max(6, 8 * Math.sqrt(scale)) : Math.max(4, 6 * Math.sqrt(scale));

		// 1) 先把所有站点标记画完，再统一画站名：
		//    这样站名不会被别的站的标记盖住，也方便让重要的站名优先占位。
		mapData.stations.forEach(st => {
			const p = worldToCanvas(st.x, st.z);
			// 换乘站画成跑道形，普通车站画成圆点
			const capsule = isInterchange(st) ? interchangeCapsule(st, radius * 2) : null;
			if (capsule) {
				stadiumPath(capsule.cx, capsule.cy, capsule.r, capsule.half, capsule.vertical);
			} else {
				ctx.beginPath();
				ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
			}
			ctx.fillStyle = '#ffffff';
			ctx.fill();
			ctx.strokeStyle = intToRgba(st.color, 1);
			ctx.lineWidth = 2;
			ctx.stroke();
		});

		// 2) 站名：缩得太小就不画；换乘站的名字优先占位，
		//    和已放下的站名重叠的站直接跳过，免得几个站挨得近时糊成一团。
		if (scale <= 0.05) return;
		const fontSize = 12;
		const transSize = 9;
		const gap = 4;
		const pad = 2;
		ctx.fillStyle = isDarkMode ? '#ffffff' : '#222233';
		ctx.textAlign = 'center';
		ctx.textBaseline = 'bottom';

		const placed = [];
		const tryDrawName = st => {
			if (placed.length >= 400) return;
			const n = splitName(st.name);
			if (!n.main) return;
			const p = worldToCanvas(st.x, st.z);
			const capsule = isInterchange(st) ? interchangeCapsule(st, radius * 2) : null;
			// 站名放在标记正上方
			const labelX = capsule ? capsule.cx : p.x;
			const labelTop = capsule
				? capsule.cy - (capsule.vertical ? capsule.half + capsule.r : capsule.r)
				: p.y - radius;
			const mainBaseline = labelTop - gap;
			const transBaseline = mainBaseline + fontSize;
			ctx.font = canvasFont(fontSize);
			let w = ctx.measureText(n.main).width;
			if (n.trans) {
				ctx.font = canvasFont(transSize);
				w = Math.max(w, ctx.measureText(n.trans).width);
			}
			const left = labelX - w / 2 - pad;
			const right = labelX + w / 2 + pad;
			const top = mainBaseline - fontSize - pad;
			const bottom = (n.trans ? transBaseline : mainBaseline) + pad;
			// 与已放下的站名重叠就跳过
			for (let i = 0; i < placed.length; i++) {
				const r = placed[i];
				if (left < r.right && right > r.left && top < r.bottom && bottom > r.top) return;
			}
			placed.push({ left: left, right: right, top: top, bottom: bottom });
			ctx.font = canvasFont(fontSize);
			ctx.fillText(n.main, labelX, mainBaseline);
			if (n.trans) {
				ctx.font = canvasFont(transSize);
				ctx.fillText(n.trans, labelX, transBaseline);
			}
		};
		// 换乘站（名字更重要）先占位，普通站后放
		mapData.stations.forEach(st => { if (isInterchange(st)) tryDrawName(st); });
		mapData.stations.forEach(st => { if (!isInterchange(st)) tryDrawName(st); });
	}

	function drawDepots() {
		if (!mapData.depots || !showDepots) return;
		mapData.depots.forEach(depot => {
			const p1 = worldToCanvas(depot.x1, depot.z1);
			const p2 = worldToCanvas(depot.x2, depot.z2);
			const x = Math.min(p1.x, p2.x);
			const y = Math.min(p1.y, p2.y);
			const w = Math.abs(p2.x - p1.x);
			const h = Math.abs(p2.y - p1.y);
			ctx.fillStyle = intToRgba(depot.color, 0.6);
			ctx.fillRect(x, y, w, h);
			ctx.strokeStyle = intToRgba(depot.color, 0.9);
			ctx.lineWidth = 1.5;
			ctx.strokeRect(x, y, w, h);
			if (scale > 0.05) {
				const cx = (p1.x + p2.x) / 2;
				const cy = (p1.y + p2.y) / 2;
				const n = splitName(depot.name);
				ctx.fillStyle = isDarkMode ? '#ffffff' : '#222233';
				ctx.textAlign = 'center';
				ctx.font = canvasFont(11);
				ctx.textBaseline = 'middle';
				if (n.trans) {
					ctx.fillText(n.main, cx, cy - 5);
					ctx.font = canvasFont(9);
					ctx.fillText(n.trans, cx, cy + 6);
				} else {
					ctx.fillText(n.main, cx, cy);
				}
			}
			const station = depot.firstStationId !== -1 ? stationMap[depot.firstStationId] : null;
			let stationPoint = null;
			if (depot.firstPlatformX !== undefined) {
				stationPoint = worldToCanvas(depot.firstPlatformX, depot.firstPlatformZ);
			} else if (station) {
				stationPoint = worldToCanvas(station.x, station.z);
			}
			ctx.strokeStyle = intToRgba(depot.color, 0.8);
			ctx.lineWidth = 1;
			if (stationPoint && depot.path && depot.path.length >= 2) {
				ctx.beginPath();
				depot.path.forEach((pt, i) => {
					const p = worldToCanvas(pt.x, pt.z);
					if (i === 0) ctx.moveTo(p.x, p.y);
					else ctx.lineTo(p.x, p.y);
				});
				ctx.stroke();
			} else if (stationPoint) {
				const sx = depot.firstPlatformX !== undefined ? depot.firstPlatformX : station.x;
				const sz = depot.firstPlatformZ !== undefined ? depot.firstPlatformZ : station.z;
				const edge = clipLineToRect(sx, sz, depot.centerX, depot.centerZ,
					Math.min(depot.x1, depot.x2), Math.min(depot.z1, depot.z2),
					Math.max(depot.x1, depot.x2), Math.max(depot.z1, depot.z2));
				const to = edge ? worldToCanvas(edge.x, edge.z) : worldToCanvas(depot.centerX, depot.centerZ);
				ctx.beginPath();
				ctx.moveTo(stationPoint.x, stationPoint.y);
				ctx.lineTo(to.x, to.y);
				ctx.stroke();
			}
		});
	}

	function drawTrains() {
		if (!mapData.trains || mapData.trains.length === 0) return;
		const size = Math.max(7, 11 * Math.sqrt(scale));
		mapData.trains.forEach(train => {
			const p = worldToCanvas(train.x, train.z);
			const color = train.routeColor !== undefined ? intToRgba(train.routeColor, 0.95) : '#ff79c6';
			roundRect(ctx, p.x - size / 2, p.y - size / 2, size, size * 0.7, 3);
			ctx.fillStyle = 'rgba(30, 30, 55, 0.95)';
			ctx.fill();
			ctx.strokeStyle = color;
			ctx.lineWidth = 2;
			ctx.stroke();
			ctx.fillStyle = color;
			ctx.fillRect(p.x - size * 0.2, p.y - size * 0.18, size * 0.4, Math.max(1.5, size * 0.18));
		});
	}

	function drawPlayers() {
		if (!players || players.length === 0) return;
		const avatarSize = Math.max(20, 32 * Math.sqrt(scale));
		players.forEach(p => {
			const pos = worldToCanvas(p.x, p.z);
			const img = avatarCache[p.uuid];
			if (img && img.complete && img.naturalWidth > 0) {
				ctx.drawImage(img, pos.x - avatarSize / 2, pos.y - avatarSize / 2, avatarSize, avatarSize);
			} else {
				ctx.beginPath();
				ctx.arc(pos.x, pos.y, avatarSize / 2, 0, Math.PI * 2);
				ctx.fillStyle = '#50fa7b';
				ctx.fill();
			}
			ctx.strokeStyle = '#50fa7b';
			ctx.lineWidth = 2;
			ctx.strokeRect(pos.x - avatarSize / 2, pos.y - avatarSize / 2, avatarSize, avatarSize);
			ctx.fillStyle = '#50fa7b';
			ctx.font = canvasFont(12, 'bold');
			ctx.textAlign = 'center';
			ctx.textBaseline = 'top';
			ctx.fillText(p.name || '', pos.x, pos.y + avatarSize / 2 + 4);
		});
	}

	function clipLineToRect(xs, zs, xc, zc, x1, z1, x2, z2) {
		const dx = xc - xs;
		const dz = zc - zs;
		let tBest = Infinity;
		if (dx !== 0) {
			[(x1 - xs) / dx, (x2 - xs) / dx].forEach(t => {
				if (t > 0 && t <= 1) {
					const z = zs + dz * t;
					if (z >= z1 && z <= z2) tBest = Math.min(tBest, t);
				}
			});
		}
		if (dz !== 0) {
			[(z1 - zs) / dz, (z2 - zs) / dz].forEach(t => {
				if (t > 0 && t <= 1) {
					const x = xs + dx * t;
					if (x >= x1 && x <= x2) tBest = Math.min(tBest, t);
				}
			});
		}
		if (tBest !== Infinity) return { x: xs + dx * tBest, z: zs + dz * tBest };
		return null;
	}

	// ===== 列车悬停提示 =====
	function getHoveredTrain(mx, my) {
		if (!mapData.trains) return null;
		let best = null;
		let bestDist = Math.max(12, 16 * Math.sqrt(scale));
		for (const train of mapData.trains) {
			const p = worldToCanvas(train.x, train.z);
			const d = Math.hypot(p.x - mx, p.y - my);
			if (d < bestDist) { bestDist = d; best = train; }
		}
		return best;
	}

	function trainTooltipHtml(train) {
		const color = train.routeColor !== undefined ? intToRgba(train.routeColor, 1) : '#ff79c6';
		let html = '<div class="tt-title"><span class="tt-dot" style="background:' + color + '"></span>' +
			escapeHtml(train.id || 'Train') + '</div>';
		html += '<div class="tt-row"><span class="tt-label">' + t('label_line') + '</span><span>' +
			escapeHtml(train.routeName || '—') + '</span></div>';
		html += '<div class="tt-row"><span class="tt-label">' + t('label_destination') + '</span><span>' +
			escapeHtml(train.destination || '—') + '</span></div>';
		html += '<div class="tt-row"><span class="tt-label">' + t('label_speed') + '</span><span>' +
			(train.speedKmh !== undefined ? train.speedKmh : '—') + ' km/h</span></div>';
		html += '<div class="tt-row"><span class="tt-label">' + t('label_consist') + '</span><span>' +
			(train.cars || '—') + '</span></div>';
		html += '<div class="tt-row"><span class="tt-label">' + t('label_passengers') + '</span><span>' +
			(train.passengers || 0) + '</span></div>';
		if (train.players && train.players.length) {
			html += '<div class="tt-row"><span class="tt-label">' + t('label_players') + '</span><span>' +
				train.players.map(escapeHtml).join(', ') + '</span></div>';
		}
		html += '<div class="tt-row"><span class="tt-label">' + t('label_status') + '</span><span>' +
			t(train.onRoute ? 'status_running' : 'status_depot') + '</span></div>';
		return html;
	}

	function updateTooltip(clientX, clientY) {
		// 路径查询模式下隐藏列车提示
		if (routeMode) {
			tooltipEl.style.display = 'none';
			return;
		}
		const rect = canvas.getBoundingClientRect();
		const train = getHoveredTrain(clientX - rect.left, clientY - rect.top);
		if (!train) {
			tooltipEl.style.display = 'none';
			return;
		}
		tooltipEl.innerHTML = trainTooltipHtml(train);
		tooltipEl.style.display = 'block';
		let left = clientX + 14;
		let top = clientY + 14;
		const tw = tooltipEl.offsetWidth;
		const th = tooltipEl.offsetHeight;
		if (left + tw > window.innerWidth) left = clientX - tw - 14;
		if (top + th > window.innerHeight) top = clientY - th - 14;
		tooltipEl.style.left = left + 'px';
		tooltipEl.style.top = top + 'px';
	}

	function roundRect(ctx, x, y, w, h, r) {
		ctx.beginPath();
		ctx.moveTo(x + r, y);
		ctx.lineTo(x + w - r, y);
		ctx.quadraticCurveTo(x + w, y, x + w, y + r);
		ctx.lineTo(x + w, y + h - r);
		ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
		ctx.lineTo(x + r, y + h);
		ctx.quadraticCurveTo(x, y + h, x, y + h - r);
		ctx.lineTo(x, y + r);
		ctx.quadraticCurveTo(x, y, x + r, y);
		ctx.closePath();
	}

	function escapeHtml(str) {
		return String(str).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
	}

	// ===== 路径查询：常量与工具 =====
	// 假定运营参数（按产品需求）
	const ROUTE_AVG_SPEED_KMH = 60;                            // 假定运营速度 60 km/h
	const ROUTE_AVG_SPEED_PER_SEC = ROUTE_AVG_SPEED_KMH / 3.6; // 1 格 = 1 米 → 16.67 格/秒
	const ROUTE_DWELL_SEC = 25;                                // 每经停一站 25 秒
	const ROUTE_TRANSFER_PENALTY_SEC = 180;                    // 每次换乘 180 秒
	// 站外步行换乘：两座车站直线距离不超过 1 km 且不共线时，允许下车步行过去换乘
	const ROUTE_WALK_MAX_METERS = 1000;
	const ROUTE_WALK_SPEED_PER_SEC = 4.3;                      // 步行速度 ≈ 4.3 格/秒（游戏内步行速度）
	const ROUTE_WALK_COLOR = '#9aa0a6';                        // 步行段与步行节点的中性配色

	// 三种查询目标（标签用 i18n key）
	const ROUTE_MODES = [
		{ key: 'shortest', label: 'route_tab_shortest' },
		{ key: 'fastest', label: 'route_tab_fastest' },
		{ key: 'fewest', label: 'route_tab_fewest' }
	];

	// 读取 CSS 变量，保证画布配色跟随主题
	function cssVar(name, fallback) {
		const v = getComputedStyle(document.body).getPropertyValue(name);
		return v && v.trim() ? v.trim() : fallback;
	}

	function refreshUiColors() {
		uiColors.start = cssVar('--route-start-color', '#2ecc71');
		uiColors.end = cssVar('--route-end-color', '#ff5555');
	}

	// 站名 / 线路名（英文时优先使用 | 之后的译名）
	function stationNameText(station) {
		if (!station) return '';
		const n = splitName(station.name);
		if (currentLang === 'en' && n.trans) return n.trans;
		return n.main;
	}

	function routeNameText(route) {
		if (!route || !route.name) return '';
		const n = splitName(route.name);
		if (currentLang === 'en' && n.trans) return n.trans;
		return n.main;
	}

	// ===== 路径查询：建图 / Dijkstra =====
	// 一段折线的累计长度；折线缺失时回退为两站中心点直线距离
	function segmentLength(seg, a, b) {
		if (seg && seg.length >= 2) {
			let len = 0;
			for (let i = 0; i < seg.length - 1; i++) {
				len += Math.hypot(seg[i + 1].x - seg[i].x, seg[i + 1].z - seg[i].z);
			}
			return len;
		}
		if (a && b) return Math.hypot(b.x - a.x, b.z - a.z);
		return 0;
	}

	// 线路度量：每段（相邻两站）的距离 + 每个车站所属的线路
	function buildRouteMetrics() {
		const segDist = {};
		const stationRoutes = {};
		const routeById = {};
		(mapData.routes || []).forEach(route => {
			routeById[route.id] = route;
			const st = route.stations || [];
			const paths = route.paths || [];
			st.forEach(sid => {
				if (!stationRoutes[sid]) stationRoutes[sid] = [];
				if (stationRoutes[sid].indexOf(route.id) === -1) stationRoutes[sid].push(route.id);
			});
			for (let i = 0; i < st.length - 1; i++) {
				const a = st[i];
				const b = st[i + 1];
				const d = segmentLength(paths[i], stationMap[a], stationMap[b]);
				// 同一段两个方向距离相同
				if (segDist[route.id + ':' + a + ':' + b] === undefined) segDist[route.id + ':' + a + ':' + b] = d;
				if (segDist[route.id + ':' + b + ':' + a] === undefined) segDist[route.id + ':' + b + ':' + a] = d;
			}
		});
		return { segDist: segDist, stationRoutes: stationRoutes, routeById: routeById };
	}

	function segmentDistance(metrics, routeId, a, b) {
		const d = metrics.segDist[routeId + ':' + a + ':' + b];
		return d === undefined ? 0 : d;
	}

	// 有向图：节点 = 「线路:车站」，边 = 乘车边 / 站内换乘边 / 站外步行边
	// kind: 'ride' 乘车 | 'transfer' 同站换线路 | 'walk' 异站步行换乘
	function buildRouteGraph(metrics) {
		const adj = {};
		const addEdge = (from, to, dist, kind) => {
			if (!adj[from]) adj[from] = [];
			adj[from].push({ to: to, dist: dist, kind: kind });
		};
		(mapData.routes || []).forEach(route => {
			const st = route.stations || [];
			// 乘车边：一条线路上相邻车站（列车双向运行，故正反各一条）
			for (let i = 0; i < st.length - 1; i++) {
				const d = segmentDistance(metrics, route.id, st[i], st[i + 1]);
				addEdge(route.id + ':' + st[i], route.id + ':' + st[i + 1], d, 'ride');
				addEdge(route.id + ':' + st[i + 1], route.id + ':' + st[i], d, 'ride');
			}
		});
		// 换乘边：同一个车站在不同线路之间（距离 0，换乘次数 +1）
		Object.keys(metrics.stationRoutes).forEach(sid => {
			const rs = metrics.stationRoutes[sid];
			for (let i = 0; i < rs.length; i++) {
				for (let j = 0; j < rs.length; j++) {
					if (i === j) continue;
					addEdge(rs[i] + ':' + sid, rs[j] + ':' + sid, 0, 'transfer');
				}
			}
		});
		addWalkEdges(metrics, addEdge);
		return adj;
	}

	// 站外步行换乘边：两座车站直线距离 ≤ 1 km、且没有任何共线时，
	// 认为下车后可以步行过去换乘（距离按两站中心点直线距离算，换乘次数仍 +1）。
	// 共线的两站不建边 —— 那本该直接坐车过去。
	function addWalkEdges(metrics, addEdge) {
		const stations = mapData.stations || [];
		for (let i = 0; i < stations.length; i++) {
			const a = stations[i];
			const ra = metrics.stationRoutes[a.id];
			if (!ra || ra.length === 0) continue;
			for (let j = i + 1; j < stations.length; j++) {
				const b = stations[j];
				const rb = metrics.stationRoutes[b.id];
				if (!rb || rb.length === 0) continue;
				// 先用包围盒排除，避免大量无谓的开方
				const dx = b.x - a.x;
				if (dx < -ROUTE_WALK_MAX_METERS || dx > ROUTE_WALK_MAX_METERS) continue;
				const dz = b.z - a.z;
				if (dz < -ROUTE_WALK_MAX_METERS || dz > ROUTE_WALK_MAX_METERS) continue;
				if (ra.some(rid => rb.indexOf(rid) !== -1)) continue;
				const d = Math.hypot(dx, dz);
				if (d > ROUTE_WALK_MAX_METERS) continue;
				ra.forEach(ridA => {
					rb.forEach(ridB => {
						addEdge(ridA + ':' + a.id, ridB + ':' + b.id, d, 'walk');
						addEdge(ridB + ':' + b.id, ridA + ':' + a.id, d, 'walk');
					});
				});
			}
		}
	}

	// 代价用 [主目标, 次目标] 表示，做字典序比较
	function costLess(a, b) {
		if (a[0] !== b[0]) return a[0] < b[0];
		return a[1] < b[1];
	}

	function costAdd(a, b) {
		return [a[0] + b[0], a[1] + b[1]];
	}

	// 三个目标使用不同的边权（不要用同一个权重跑三次）
	function edgeCost(mode, dist, kind) {
		if (kind === 'transfer') {
			if (mode === 'fastest') return [ROUTE_TRANSFER_PENALTY_SEC, 1];
			if (mode === 'fewest') return [1, 0];
			return [0, 1]; // 最短：换乘不计距离，仅作为次要比较
		}
		if (kind === 'walk') {
			// 站外步行也是一次换乘，但耗时按步速算、距离算进总里程
			if (mode === 'fastest') return [dist / ROUTE_WALK_SPEED_PER_SEC, 1];
			if (mode === 'fewest') return [1, dist];
			return [dist, 1];
		}
		if (mode === 'fastest') return [dist / ROUTE_AVG_SPEED_PER_SEC + ROUTE_DWELL_SEC, 0];
		if (mode === 'fewest') return [0, dist];
		return [dist, 0]; // 最短：乘车距离
	}

	// 简易二叉堆
	function heapPush(heap, item) {
		heap.push(item);
		let i = heap.length - 1;
		while (i > 0) {
			const p = (i - 1) >> 1;
			if (!costLess(heap[i].cost, heap[p].cost)) break;
			const tmp = heap[p];
			heap[p] = heap[i];
			heap[i] = tmp;
			i = p;
		}
	}

	function heapPop(heap) {
		const top = heap[0];
		const last = heap.pop();
		if (heap.length > 0) {
			heap[0] = last;
			let i = 0;
			while (true) {
				const l = i * 2 + 1;
				const r = l + 1;
				let m = i;
				if (l < heap.length && costLess(heap[l].cost, heap[m].cost)) m = l;
				if (r < heap.length && costLess(heap[r].cost, heap[m].cost)) m = r;
				if (m === i) break;
				const tmp = heap[m];
				heap[m] = heap[i];
				heap[i] = tmp;
				i = m;
			}
		}
		return top;
	}

	// Dijkstra：起点用该车站的任意线路节点作源点，终点同理
	function dijkstraRoute(metrics, adj, startStationId, endStationId, mode) {
		const startRoutes = metrics.stationRoutes[startStationId] || [];
		if (startRoutes.length === 0) return null;
		const best = {};
		const prev = {};
		const heap = [];
		startRoutes.forEach(rid => {
			const key = rid + ':' + startStationId;
			best[key] = [0, 0];
			prev[key] = null;
			heapPush(heap, { node: key, cost: [0, 0] });
		});
		let endKey = null;
		while (heap.length > 0) {
			const cur = heapPop(heap);
			if (best[cur.node] && costLess(best[cur.node], cur.cost)) continue; // 过期条目
			const stationId = parseInt(cur.node.split(':')[1], 10);
			if (stationId === endStationId) { endKey = cur.node; break; }
			const edges = adj[cur.node] || [];
			for (let i = 0; i < edges.length; i++) {
				const e = edges[i];
				const nc = costAdd(cur.cost, edgeCost(mode, e.dist, e.kind));
				if (!best[e.to] || costLess(nc, best[e.to])) {
					best[e.to] = nc;
					prev[e.to] = { node: cur.node, kind: e.kind };
					heapPush(heap, { node: e.to, cost: nc });
				}
			}
		}
		if (!endKey) return null;
		// 回溯节点序列与每条边的类型（乘车 / 换乘 / 步行）
		const nodes = [];
		const kinds = [];
		let node = endKey;
		while (node) {
			nodes.unshift(node);
			const p = prev[node];
			if (!p) break; // 起点节点没有前驱
			kinds.unshift(p.kind);
			node = p.node;
		}
		return { nodes: nodes, kinds: kinds };
	}

	// 图节点键 "线路:车站" → { routeId, stationId }
	function parseNodeKey(key) {
		const parts = key.split(':');
		return { routeId: parseInt(parts[0], 10), stationId: parseInt(parts[1], 10) };
	}

	// 两座车站的直线距离（步行换乘按直线估算）
	function stationDistance(a, b) {
		if (!a || !b) return 0;
		return Math.hypot(b.x - a.x, b.z - a.z);
	}

	// 乘车段的行驶方向终点站：线路车站序列是单向有序的，比较上车站与下车站
	// 在该序列中的位置即可判断列车开往哪一端（返回那一端的终点站 id）。
	// 环线 / 支线导致站序非线性、或找不到车站时返回 null，此时不显示方向。
	function legTerminalStation(route, stations) {
		if (!route || !stations || stations.length < 2) return null;
		const seq = route.stations || [];
		const iFrom = seq.indexOf(stations[0]);
		const iTo = seq.indexOf(stations[stations.length - 1]);
		if (iFrom === -1 || iTo === -1 || iFrom === iTo) return null;
		return iTo > iFrom ? seq[seq.length - 1] : seq[0];
	}

	// 把节点序列整理成一条可选方案：步骤序列（乘车段 / 步行段）+ 统计
	function computeRouteOption(metrics, adj, mode, startId, endId) {
		const path = dijkstraRoute(metrics, adj, startId, endId, mode);
		if (!path) return null;
		const nodes = path.nodes;
		const kinds = path.kinds;

		// 节点序列 + 边类型 → 步骤序列：
		//   ride     同一条线路上连续行驶，合并成一个乘车段
		//   walk     异站步行衔接，单独成一个步行段
		//   transfer 同站换线路，不产生步骤（下车即上另一条线）
		const steps = [];
		let ride = null;
		for (let i = 0; i < nodes.length - 1; i++) {
			const kind = kinds[i];
			const from = parseNodeKey(nodes[i]);
			const to = parseNodeKey(nodes[i + 1]);
			if (kind === 'ride') {
				if (!ride || ride.routeId !== from.routeId) {
					ride = { type: 'ride', routeId: from.routeId, stations: [from.stationId] };
					steps.push(ride);
				}
				ride.stations.push(to.stationId);
			} else if (kind === 'walk') {
				ride = null;
				steps.push({ type: 'walk', fromStation: from.stationId, toStation: to.stationId });
			} else {
				ride = null;
			}
		}
		if (steps.length === 0) return null;

		// 合并相邻的步行段：换乘边不产生步骤，所以「步行到 B 站、再从 B 站步行到 C 站」
		// 会表现为两个相邻的 walk 步骤，这里合并为一段直接从 A 步行到 C，避免重复步行。
		for (let i = 0; i < steps.length - 1; ) {
			if (steps[i].type === 'walk' && steps[i + 1].type === 'walk') {
				steps[i].toStation = steps[i + 1].toStation;
				steps.splice(i + 1, 1);
			} else {
				i++;
			}
		}

		// 补上乘车段的线路信息与步行段的距离、步行方位
		steps.forEach(s => {
			if (s.type === 'walk') {
				const a = stationMap[s.fromStation];
				const b = stationMap[s.toStation];
				s.dist = stationDistance(a, b);
				// 步行换乘也给出「往哪个方位走」的指引
				s.dirKey = walkDirectionKey(a, b);
				return;
			}
			const route = metrics.routeById[s.routeId];
			s.routeName = route ? route.name : ('#' + s.routeId);
			s.routeColor = route ? route.color : 0xFFFFFFFF;
			// 该段要乘坐的方向（列车开往的终点站）
			s.terminalId = legTerminalStation(route, s.stations);
		});

		const legs = steps.filter(s => s.type === 'ride');
		let dist = 0;
		let hops = 0;
		let walkDist = 0;
		steps.forEach(s => {
			if (s.type === 'walk') {
				walkDist += s.dist;
				return;
			}
			for (let i = 0; i < s.stations.length - 1; i++) {
				dist += segmentDistance(metrics, s.routeId, s.stations[i], s.stations[i + 1]);
				hops++;
			}
		});
		// 每换一条线路算一次换乘（站内换乘与步行换乘都算）
		const transfers = Math.max(0, legs.length - 1);
		const timeSec = dist / ROUTE_AVG_SPEED_PER_SEC + hops * ROUTE_DWELL_SEC
			+ walkDist / ROUTE_WALK_SPEED_PER_SEC + transfers * ROUTE_TRANSFER_PENALTY_SEC;
		return {
			mode: mode,
			steps: steps,
			legs: legs,
			dist: dist + walkDist,
			walkDist: walkDist,
			hops: hops,
			transfers: transfers,
			timeSec: timeSec
		};
	}

	// 查询三条候选路线
	function computeRoutes() {
		if (routeStartId === null || routeEndId === null) return;
		legExpandState = {};
		activeRouteTab = 0;
		startWalkInfo = null;
		if (routeStartId === routeEndId) {
			routeResult = { options: [], message: 'route_same_station' };
			return;
		}
		const metrics = buildRouteMetrics();  // 建图只做一次，三种目标共用
		const adj = buildRouteGraph(metrics);
		const options = [];
		ROUTE_MODES.forEach(m => {
			const opt = computeRouteOption(metrics, adj, m.key, routeStartId, routeEndId);
			if (opt) {
				opt.labelKey = m.label;
				options.push(opt);
			}
		});
		routeResult = { options: options, message: options.length ? null : 'route_unreachable' };
		// 起点来自「我的位置」时，算出从当前位置步行到起点站的方位与距离
		startWalkInfo = computeStartWalk();
		// 查询成功即把路线同步到游戏内导航（识别到玩家时）
		if (options.length > 0) pushNavTask();
	}

	// 从「我的位置」步行到起点站的指引；起点不是通过我的位置选的则返回 null
	function computeStartWalk() {
		if (!routeFromMyLocation || !myLocation || routeStartId === null) return null;
		const st = stationMap[routeStartId];
		if (!st) return null;
		return {
			dist: stationDistance(myLocation, st),
			dirKey: walkDirectionKey(myLocation, st)
		};
	}

	// ===== 路径查询：面板渲染 =====
	function formatDistance(meters) {
		if (meters >= 1000) return (meters / 1000).toFixed(2) + ' km';
		return Math.round(meters) + ' m';
	}

	function formatMinutes(sec) {
		return t('route_minutes', { n: Math.max(1, Math.round(sec / 60)) });
	}

	// 列车线路名与线路名匹配
	function trainNameMatches(trainRouteName, legRouteName) {
		if (!trainRouteName || !legRouteName) return false;
		if (trainRouteName === legRouteName) return true;
		return splitName(legRouteName).main === trainRouteName;
	}

	// 实时信息：该段线路上离上车站最近的列车，距离 / 速度 估算到站时间
	function estimateArrival(leg, station) {
		if (!leg || !station || !mapData.trains) return null;
		let best = null;
		let bestDist = Infinity;
		mapData.trains.forEach(tr => {
			if (!tr || tr.x === undefined || tr.z === undefined) return;
			if (!trainNameMatches(tr.routeName, leg.routeName)) return;
			const d = Math.hypot(tr.x - station.x, tr.z - station.z);
			if (d < bestDist) { bestDist = d; best = tr; }
		});
		if (!best) return null;
		const kmh = (best.speedKmh !== undefined && best.speedKmh > 0) ? best.speedKmh : ROUTE_AVG_SPEED_KMH;
		const sec = bestDist / (kmh / 3.6);
		return { minutes: Math.max(1, Math.ceil(sec / 60)), train: best };
	}

	function routeRealtimeHtml(leg, stationId) {
		const info = estimateArrival(leg, stationMap[stationId]);
		if (!info) return '<div class="route-realtime">' + escapeHtml(t('route_no_train')) + '</div>';
		return '<div class="route-realtime">' + escapeHtml(t('route_realtime', { n: info.minutes })) + '</div>';
	}

	function routeNodeHtml(type, color, stationId, subText, extraHtml) {
		const icon = (type === 'start' || type === 'end') ? '<span class="route-node-icon">🚇</span>' : '';
		return '<div class="route-node ' + type + '" style="--node-color:' + color + '">' +
			'<div class="route-node-marker ' + type + '">' + icon + '</div>' +
			'<div class="route-node-text">' +
				'<div class="route-node-name">' + escapeHtml(stationNameText(stationMap[stationId])) + '</div>' +
				(subText ? '<div class="route-node-sub">' + escapeHtml(subText) + '</div>' : '') +
				(extraHtml || '') +
			'</div>' +
		'</div>';
	}

	// 两个节点之间的乘车段：默认一行「开往 X · 乘坐 Y 站」，可展开途经站
	function routeRideHtml(leg, index, color) {
		const rideCount = leg.stations.length - 1;
		const mids = leg.stations.slice(1, leg.stations.length - 1);
		const empty = mids.length === 0;
		const expanded = !!legExpandState[index];
		// 乘车方向：显示列车开往的终点站（站序非线性时 terminalId 为 null，不显示）
		const dirStation = (leg.terminalId !== undefined && leg.terminalId !== null) ? stationMap[leg.terminalId] : null;
		const dirText = dirStation ? t('route_towards', { station: stationNameText(dirStation) }) : '';
		let html = '<div class="route-ride' + (expanded ? ' expanded' : '') + '" data-leg="' + index + '">' +
			'<div class="route-ride-line" style="background:' + color + '"></div>' +
			'<div class="route-ride-body">' +
				'<button class="route-ride-toggle' + (empty ? ' empty' : '') + '"' + (empty ? ' disabled' : '') + '>' +
					'<span class="route-ride-linetag" style="border-color:' + color + ';color:' + color + '">' +
						escapeHtml(routeNameText({ name: leg.routeName })) +
					'</span>' +
					'<span class="route-ride-count">' +
						(dirText ? '<span class="route-ride-dir" style="color:' + color + '">' + escapeHtml(dirText) + '</span> ' : '') +
						escapeHtml(t('route_ride_stations', { n: rideCount })) +
					'</span>' +
					'<span class="route-ride-arrow">▾</span>' +
				'</button>';
		if (!empty) {
			html += '<div class="route-ride-stations">' +
				mids.map(id => '<div class="route-ride-station">' + escapeHtml(stationNameText(stationMap[id])) + '</div>').join('') +
			'</div>';
		}
		html += '</div></div>';
		return html;
	}

	// 站外步行段：虚线 + 「步行 X」，并给出方位指引
	function routeWalkHtml(dist, dirKey) {
		const dirText = dirKey ? t('route_walk_towards', { dir: t(dirKey) }) : '';
		return '<div class="route-walk">' +
			'<div class="route-walk-line"></div>' +
			'<div class="route-walk-body">' +
				'<span class="route-walk-label">🚶 ' +
					escapeHtml(t('route_walk', { distance: formatDistance(dist) })) +
					(dirText ? ' <span class="route-walk-dir">' + escapeHtml(dirText) + '</span>' : '') +
				'</span>' +
			'</div>' +
		'</div>';
	}

	// 从上到下连续的流程：起点 → 乘车段 → 换乘站 →（步行段）→ 乘车段 → … → 终点
	function routeFlowHtml(opt) {
		const steps = opt.steps;
		const firstRideColor = opt.legs.length > 0 ? intToRgba(opt.legs[0].routeColor, 1) : ROUTE_WALK_COLOR;
		let html = '<div class="route-flow">';
		// 起点来自「我的位置」：在最上方加一条步行指引（往哪个方位走到起点站）
		if (startWalkInfo) {
			html += '<div class="route-from-loc">🚶 ' +
				escapeHtml(t('route_from_location', {
					dir: t(startWalkInfo.dirKey),
					distance: formatDistance(startWalkInfo.dist),
					station: stationNameText(stationMap[routeStartId])
				})) + '</div>';
		}
		let rideIndex = -1;
		let lastStation = null;   // 上一步结束时所在的车站
		let lastColor = null;     // 乘车到该站时的线路色
		let startRendered = false;

		steps.forEach(step => {
			if (step.type === 'walk') {
				// 步行换乘：先在「下车的那座站」收尾，再画步行段，下一段上车站由后续节点渲染
				if (!startRendered) {
					html += routeNodeHtml('start', firstRideColor, step.fromStation, t('route_start_tag'), '');
					startRendered = true;
				} else {
					html += routeNodeHtml('transfer', lastColor || ROUTE_WALK_COLOR, step.fromStation,
						t('route_walk_tag'), '');
				}
				html += routeWalkHtml(step.dist, step.dirKey);
				lastStation = step.toStation;
				lastColor = null;
				return;
			}
			rideIndex++;
			const color = intToRgba(step.routeColor, 1);
			const boardId = step.stations[0];
			if (!startRendered) {
				html += routeNodeHtml('start', color, boardId, t('route_start_tag'), routeRealtimeHtml(step, boardId));
				startRendered = true;
			} else {
				// 换乘站：既是上一段的终点，也是本段的上车站，带本段的实时信息与换乘提示
				const hint = '<div class="route-transfer-hint">' + escapeHtml(t('route_transfer_hint', {
					station: stationNameText(stationMap[boardId]),
					line: routeNameText({ name: step.routeName })
				})) + '</div>';
				html += routeNodeHtml('transfer', color, boardId, '', routeRealtimeHtml(step, boardId) + hint);
			}
			html += routeRideHtml(step, rideIndex, color);
			lastStation = step.stations[step.stations.length - 1];
			lastColor = color;
		});
		if (lastStation !== null) {
			html += routeNodeHtml('end', lastColor || firstRideColor, lastStation, t('route_end_tag'), '');
		}
		html += '</div>';
		return html;
	}

	function routeSummaryHtml(opt) {
		let html = '<div class="route-summary">';
		html += '<div class="route-summary-item"><span class="route-summary-label">' + escapeHtml(t('route_distance')) + '</span>' +
			'<span class="route-summary-value">' + escapeHtml(formatDistance(opt.dist)) + '</span></div>';
		html += '<div class="route-summary-item"><span class="route-summary-label">' + escapeHtml(t('route_time')) + '</span>' +
			'<span class="route-summary-value">' + escapeHtml(formatMinutes(opt.timeSec)) + '</span></div>';
		html += '<div class="route-summary-item"><span class="route-summary-label">' + escapeHtml(t('route_transfers')) + '</span>' +
			'<span class="route-summary-value">' + opt.transfers + '</span></div>';
		html += '</div>';
		return html;
	}

	function renderRouteTabs() {
		const options = routeResult ? routeResult.options : null;
		if (!options || options.length === 0) {
			routeTabsEl.innerHTML = '';
			return;
		}
		let html = '';
		options.forEach((opt, i) => {
			html += '<button class="route-tab' + (i === activeRouteTab ? ' active' : '') + '" data-tab="' + i + '">' +
				escapeHtml(t(opt.labelKey)) + '</button>';
		});
		routeTabsEl.innerHTML = html;
		routeTabsEl.querySelectorAll('.route-tab').forEach(btn => {
			btn.addEventListener('click', () => {
				activeRouteTab = parseInt(btn.getAttribute('data-tab'), 10) || 0;
				legExpandState = {};
				renderRouteTabs();
				renderRouteDetail();
				// 换方案后立刻同步给游戏内导航：rev 变了，游戏内会切到新路线
				pushNavTask();
			});
		});
	}

	function bindRideToggles() {
		routeDetailEl.querySelectorAll('.route-ride-toggle').forEach(btn => {
			btn.addEventListener('click', () => {
				if (btn.classList.contains('empty')) return;
				const box = btn.closest('.route-ride');
				if (!box) return;
				const idx = parseInt(box.getAttribute('data-leg'), 10) || 0;
				legExpandState[idx] = !legExpandState[idx];
				box.classList.toggle('expanded', !!legExpandState[idx]);
			});
		});
	}

	function renderRouteDetail() {
		if (!routeResult) {
			routeDetailEl.innerHTML = '';
			return;
		}
		if (routeResult.message === 'route_same_station') {
			routeDetailEl.innerHTML = '<div class="route-unreachable">' + escapeHtml(t('route_same_station')) + '</div>';
			return;
		}
		if (routeResult.options.length === 0) {
			routeDetailEl.innerHTML = '<div class="route-unreachable">' + escapeHtml(t('route_unreachable')) + '</div>';
			return;
		}
		const opt = routeResult.options[activeRouteTab] || routeResult.options[0];
		routeDetailEl.innerHTML = routeSummaryHtml(opt) + routeFlowHtml(opt);
		bindRideToggles();
	}

	function updateRoutePanel() {
		if (!routePanel) return;
		routePanelTitle.textContent = t('route_panel_title');
		routeCloseBtn.title = t('route_close');
		routeSearchBtn.textContent = t('route_search');
		routeSearchBtn.disabled = !(routeStartId !== null && routeEndId !== null);
		routeClearBtn.textContent = t('route_clear');
		routeMyLocationBtn.textContent = t('my_location');
		routeMyLocationBtn.title = t('my_location_title');
		routeBtn.title = t('route_btn_title');
		if (!routeMode) return;
		if (routeStartId === null) {
			routeHintEl.textContent = t('route_pick_start');
		} else if (routeEndId === null) {
			routeHintEl.textContent = t('route_pick_end');
		} else {
			routeHintEl.textContent = t('route_hint_both', {
				start: stationNameText(stationMap[routeStartId]),
				end: stationNameText(stationMap[routeEndId])
			});
		}
		renderRouteTabs();
		renderRouteDetail();
	}

	// ===== 我的位置 / 方位指引 =====
	// 以 Minecraft 坐标约定判断方位：-Z 北、+X 东、+Z 南、-X 西。
	// 返回 8 方位之一的 i18n key（北/东北/东/…）。
	function walkDirectionKey(from, to) {
		if (!from || !to) return null;
		const dx = to.x - from.x; // +X = 东
		const dz = to.z - from.z; // +Z = 南
		if (dx === 0 && dz === 0) return null;
		// atan2(东分量, 北分量)：0 = 正北，顺时针增大
		const angle = Math.atan2(dx, -dz);
		const idx = ((Math.round(angle / (Math.PI / 4)) % 8) + 8) % 8;
		return ['dir_n', 'dir_ne', 'dir_e', 'dir_se', 'dir_s', 'dir_sw', 'dir_w', 'dir_nw'][idx];
	}

	// 距给定坐标最近的车站
	function nearestStation(x, z) {
		let best = null;
		let bestDist = Infinity;
		(mapData.stations || []).forEach(st => {
			const d = Math.hypot(st.x - x, st.z - z);
			if (d < bestDist) { bestDist = d; best = st; }
		});
		return best;
	}

	// 按请求来源 IP 问服务端「我是哪个在线玩家」。
	// 返回：玩家对象 / null（确实不在游戏中）/ undefined（请求失败，状态未知）
	async function fetchWhoAmI() {
		let resp;
		try {
			resp = await fetch('/api/whoami');
		} catch (e) {
			return undefined;
		}
		if (!resp.ok) return undefined;
		try {
			const info = await resp.json();
			if (info && info.ok) {
				myPlayer = info;
				myLocation = { x: info.x, z: info.z };
				showUserCard(info);
				return info;
			}
		} catch (e) {
			return undefined;
		}
		return null;
	}

	function showUserCard(player) {
		if (!player) return;
		userAvatar.src = '/avatar/' + player.uuid + '.png';
		userNameEl.textContent = player.name || '';
		userCard.style.display = 'block';
		document.body.classList.add('has-user');
	}

	function hideUserCard() {
		userCard.style.display = 'none';
		userMenu.style.display = 'none';
		document.body.classList.remove('has-user');
	}

	// 点「我的位置」：把起点设为离我最近的车站，并记录步行方位；
	// 若终点已选好则直接查询（查询会自动把路线同步到游戏内导航）。
	async function useMyLocation() {
		routeMyLocationBtn.disabled = true;
		const info = await fetchWhoAmI();
		routeMyLocationBtn.disabled = false;
		if (!info) {
			routeHintEl.textContent = t('route_not_in_game');
			return;
		}
		const st = nearestStation(info.x, info.z);
		if (!st) {
			routeHintEl.textContent = t('route_my_no_station');
			return;
		}
		routeStartId = st.id;
		routeFromMyLocation = true;
		lastMyPos = { x: info.x, z: info.z };
		routeResult = null;
		activeRouteTab = 0;
		legExpandState = {};
		if (routeEndId !== null) computeRoutes();
		updateRoutePanel();
		render();
	}

	// ===== 我的位置：定时刷新 =====
	// 位置只靠点击定位一次是不够的：玩家在游戏里走动后绿点、步行方位与距离、
	// 最近车站都会过期。这里每 2 秒重新问一次服务端，位置有变化就全部跟着更新，
	// 并把新的步行指引同步给游戏内导航。
	const MY_LOCATION_REFRESH_MS = 2000;

	async function refreshMyLocation() {
		if (routeMyLocationBtn.disabled) return;   // 手动定位还没结束，让它先完成
		const info = await fetchWhoAmI();
		if (info === undefined) return;            // 请求失败：保持原状
		if (info === null) {                       // 已经不在游戏中
			if (myPlayer) {
				myPlayer = null;
				myLocation = null;
				lastMyPos = null;
				hideUserCard();
				if (routeFromMyLocation) {
					routeFromMyLocation = false;
					startWalkInfo = null;
					updateRoutePanel();
				}
				render();
			}
			return;
		}
		// 没走动就不做任何事，避免无谓重绘
		const moved = !lastMyPos || Math.hypot(info.x - lastMyPos.x, info.z - lastMyPos.z) >= 1;
		lastMyPos = { x: info.x, z: info.z };
		if (!moved) return;
		if (!routeFromMyLocation || !routeMode) {
			render();
			return;
		}
		const st = nearestStation(info.x, info.z);
		if (st && st.id !== routeStartId) {
			// 走动后最近的车站变了：起点站跟着换，重新查一次路线
			routeStartId = st.id;
			routeResult = null;
			activeRouteTab = 0;
			legExpandState = {};
			if (routeEndId !== null && routeEndId !== routeStartId) {
				computeRoutes();
			} else {
				startWalkInfo = null;
			}
		} else {
			// 起点站没变：只刷新步行距离 / 方位，再同步给游戏内导航
			startWalkInfo = computeStartWalk();
			pushNavTask();
		}
		updateRoutePanel();
		render();
	}

	// 把当前查询到的路线同步到游戏内导航（POST 给服务端，客户端轮询领取）
	function pushNavTask() {
		if (!myPlayer || !routeResult || !routeResult.options || routeResult.options.length === 0) return;
		if (routeStartId === null || routeEndId === null) return;
		const opt = routeResult.options[activeRouteTab] || routeResult.options[0];
		const startStation = stationMap[routeStartId];
		const endStation = stationMap[routeEndId];
		// 同一条路线（起终点 + 乘车步骤一致）的重复下发不会重置游戏内进度；
		// 换方案 / 换起终点会得到新的 rev，游戏内随之切换。
		// 步行距离不参与 rev，否则玩家每走一步都会换 rev，游戏内进度会被反复清零。
		const rev = routeStartId + '|' + routeEndId + '|' + opt.labelKey + '|' + opt.steps.map(s => s.type === 'walk'
			? 'w' + s.toStation
			: 'r' + s.routeName + ':' + s.stations[0] + '>' + s.stations[s.stations.length - 1]).join(',');
		if (rev !== lastNavRev) {
			lastNavRev = rev;
			navStartedAt = Math.floor(Date.now() / 1000);
		}
		const task = {
			rev: rev,
			startedAt: navStartedAt,
			from: { name: stationNameText(startStation), x: startStation.x, z: startStation.z },
			to: { name: stationNameText(endStation), x: endStation.x, z: endStation.z },
			dist: Math.round(opt.dist),
			timeSec: Math.round(opt.timeSec),
			transfers: opt.transfers,
			startWalk: (startWalkInfo && myLocation) ? {
				dir: t(startWalkInfo.dirKey),
				distance: Math.round(startWalkInfo.dist),
				x: myLocation.x,
				z: myLocation.z,
				station: stationNameText(startStation)
			} : null,
			steps: opt.steps.map(s => s.type === 'walk'
				? {
					type: 'walk',
					to: stationNameText(stationMap[s.toStation]),
					distance: Math.round(s.dist),
					dir: s.dirKey ? t(s.dirKey) : '',
					x: stationMap[s.toStation] ? stationMap[s.toStation].x : 0,
					z: stationMap[s.toStation] ? stationMap[s.toStation].z : 0
				}
				: {
					type: 'ride',
					line: routeNameText({ name: s.routeName }),
					color: s.routeColor,
					towards: (s.terminalId !== undefined && s.terminalId !== null)
						? stationNameText(stationMap[s.terminalId]) : '',
					rideCount: s.stations.length - 1,
					from: stationNameText(stationMap[s.stations[0]]),
					to: stationNameText(stationMap[s.stations[s.stations.length - 1]]),
					x: stationMap[s.stations[s.stations.length - 1]] ? stationMap[s.stations[s.stations.length - 1]].x : 0,
					z: stationMap[s.stations[s.stations.length - 1]] ? stationMap[s.stations[s.stations.length - 1]].z : 0
				})
		};
		fetch('/api/nav', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ uuid: myPlayer.uuid, task: task })
		}).catch(() => { /* 同步失败不影响网页 */ });
	}

	// ===== 行程记录 =====
	async function loadTrips() {
		if (!myPlayer) {
			const info = await fetchWhoAmI();
			if (!info) {
				tripsList.innerHTML = '<div class="trips-empty">' + escapeHtml(t('route_not_in_game')) + '</div>';
				return;
			}
		}
		tripsList.innerHTML = '<div class="trips-empty">' + escapeHtml(t('loading')) + '</div>';
		try {
			const resp = await fetch('/api/trips?uuid=' + encodeURIComponent(myPlayer.uuid));
			currentTrips = await resp.json() || [];
		} catch (e) {
			currentTrips = [];
		}
		renderTrips();
	}

	function tripStatusText(trip) {
		return t(trip.status === 'completed' ? 'trip_status_completed' : 'trip_status_stopped');
	}

	function formatTripDate(ts) {
		if (!ts) return '';
		const d = new Date(ts * 1000);
		const p = n => String(n).padStart(2, '0');
		return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
			' ' + p(d.getHours()) + ':' + p(d.getMinutes());
	}

	function tripMetaText(trip) {
		const parts = [];
		if (trip.time) parts.push(formatTripDate(trip.time));
		if (trip.dist !== undefined) parts.push(formatDistance(trip.dist));
		if (trip.timeSec !== undefined) parts.push(formatMinutes(trip.timeSec));
		if (trip.transfers !== undefined) parts.push(t('route_transfers') + ' ' + trip.transfers);
		return parts.join(' · ');
	}

	function renderTrips() {
		if (!currentTrips || currentTrips.length === 0) {
			tripsList.innerHTML = '<div class="trips-empty">' + escapeHtml(t('trips_empty')) + '</div>';
			return;
		}
		let html = '';
		currentTrips.forEach(trip => {
			const completed = trip.status === 'completed';
			const from = (trip.from && trip.from.name) || '';
			const to = (trip.to && trip.to.name) || '';
			html += '<div class="trip-item" data-id="' + escapeHtml(trip.id || '') + '">' +
				'<div class="trip-item-head">' +
					'<span class="trip-item-route">' + escapeHtml(from + ' → ' + to) + '</span>' +
					'<span class="trip-item-status ' + (completed ? 'completed' : 'stopped') + '">' +
						escapeHtml(tripStatusText(trip)) + '</span>' +
				'</div>' +
				'<div class="trip-item-meta">' + escapeHtml(tripMetaText(trip)) + '</div>' +
				'<div class="trip-item-actions">' +
					'<button data-act="ticket">' + escapeHtml(t('trip_ticket')) + '</button>' +
					'<button data-act="delete">' + escapeHtml(t('trip_delete')) + '</button>' +
				'</div>' +
			'</div>';
		});
		tripsList.innerHTML = html;
		tripsList.querySelectorAll('.trip-item').forEach(item => {
			const id = item.getAttribute('data-id');
			const trip = currentTrips.find(x => x.id === id);
			item.querySelector('[data-act="delete"]').addEventListener('click', () => {
				showConfirm(t('trip_delete_confirm'), () => deleteTrip(id));
			});
			item.querySelector('[data-act="ticket"]').addEventListener('click', () => openTicket(trip));
		});
	}

	async function deleteTrip(id) {
		if (!myPlayer) return;
		try {
			await fetch('/api/trips/delete', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ uuid: myPlayer.uuid, id: id })
			});
		} catch (e) { /* 静默 */ }
		currentTrips = currentTrips.filter(x => x.id !== id);
		renderTrips();
	}

	// 通用确认框
	function showConfirm(message, onOk) {
		pendingConfirm = onOk;
		confirmTitle.textContent = t('confirm_title');
		confirmMessage.textContent = message;
		confirmOk.textContent = t('confirm_ok');
		confirmCancel.textContent = t('confirm_cancel');
		confirmDialog.style.display = 'flex';
	}

	// ===== 纪念票根 =====
	function openTicket(trip) {
		if (!trip) return;
		currentTicket = { trip: trip, dataUrl: buildTicketImage(trip) };
		ticketPreview.src = currentTicket.dataUrl;
		ticketTitle.textContent = t('ticket_title');
		ticketSave.textContent = t('ticket_save');
		ticketPrint.textContent = t('ticket_print');
		ticketCancel.textContent = t('ticket_cancel');
		ticketDialog.style.display = 'flex';
	}

	// 用 canvas 画一张纪念票根，返回 PNG dataURL
	function buildTicketImage(trip) {
		const W = 840;
		const H = 420;
		const cv = document.createElement('canvas');
		cv.width = W;
		cv.height = H;
		const g = cv.getContext('2d');
		const font = '"Microsoft YaHei", "Segoe UI", sans-serif';
		g.fillStyle = '#f7f3e8';
		g.fillRect(0, 0, W, H);
		g.strokeStyle = '#2a2a3a';
		g.lineWidth = 6;
		g.strokeRect(18, 18, W - 36, H - 36);

		g.textBaseline = 'alphabetic';
		g.textAlign = 'left';
		g.fillStyle = '#2a2a3a';
		g.font = 'bold 32px ' + font;
		g.fillText(t('ticket_heading'), 48, 92);

		// 右上角状态章
		const completed = trip.status === 'completed';
		g.textAlign = 'right';
		g.fillStyle = completed ? '#1f9d55' : '#8a8f98';
		g.font = 'bold 22px ' + font;
		g.fillText(tripStatusText(trip), W - 48, 92);

		g.strokeStyle = '#c9c0a8';
		g.lineWidth = 2;
		g.beginPath();
		g.moveTo(48, 118);
		g.lineTo(W - 48, 118);
		g.stroke();

		const from = (trip.from && trip.from.name) || '-';
		const to = (trip.to && trip.to.name) || '-';
		g.textAlign = 'left';
		g.fillStyle = '#5a5a6e';
		g.font = '15px ' + font;
		g.fillText(t('ticket_from'), 48, 166);
		g.fillText(t('ticket_to'), 470, 166);
		g.fillStyle = '#1a1a2e';
		g.font = 'bold 40px ' + font;
		g.fillText(from, 48, 218);
		g.fillText(to, 470, 218);
		g.fillStyle = '#c9c0a8';
		g.font = 'bold 38px ' + font;
		g.fillText('→', 410, 218);

		// 信息四宫格
		const cells = [
			[t('ticket_date'), formatTripDate(trip.time) || '-'],
			[t('ticket_distance'), trip.dist !== undefined ? formatDistance(trip.dist) : '-'],
			[t('ticket_time'), trip.timeSec !== undefined ? formatMinutes(trip.timeSec) : '-'],
			[t('ticket_transfers'), trip.transfers !== undefined ? String(trip.transfers) : '-']
		];
		cells.forEach((cell, i) => {
			const x = 48 + (i % 2) * 422;
			const y = 285 + Math.floor(i / 2) * 46;
			g.fillStyle = '#5a5a6e';
			g.font = '14px ' + font;
			g.fillText(cell[0], x, y);
			g.fillStyle = '#1a1a2e';
			g.font = 'bold 20px ' + font;
			g.fillText(cell[1], x + 92, y);
		});

		// 底部小字 + 假条码
		g.fillStyle = '#9a9382';
		g.font = '13px ' + font;
		g.fillText(t('ticket_footer'), 48, H - 40);
		let bx = W - 260;
		for (let i = 0; i < 42; i++) {
			g.fillStyle = i % 3 === 0 ? '#2a2a3a' : '#6a6478';
			const bw = (i % 2 === 0) ? 3 : 1.5;
			g.fillRect(bx, H - 60, bw, 26);
			bx += bw + 2.5;
		}
		return cv.toDataURL('image/png');
	}

	function saveTicket() {
		if (!currentTicket) return;
		const a = document.createElement('a');
		a.href = currentTicket.dataUrl;
		a.download = 'mtr-ticket-' + (currentTicket.trip.id || Date.now()) + '.png';
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
	}

	function printTicket() {
		if (!currentTicket) return;
		const w = window.open('', '_blank');
		if (!w) return;
		w.document.write('<html><head><title>' + escapeHtml(t('ticket_title')) + '</title></head>' +
			'<body style="margin:0">' +
			'<img src="' + currentTicket.dataUrl + '" style="width:100%" ' +
			'onload="window.focus();window.print();">' +
			'</body></html>');
		w.document.close();
	}

	// ===== 路径查询：模式与选点 =====
	function setRouteMode(on) {
		routeMode = on;
		document.body.classList.toggle('route-mode', on);
		routeBtn.classList.toggle('active', on);
		routePanel.style.display = on ? 'flex' : 'none';
		if (!on) tooltipEl.style.display = 'none';
		updateRoutePanel();
		render();
	}

	function getStationAt(mx, my) {
		if (!mapData.stations) return null;
		const radius = Math.max(14, 10 * Math.sqrt(scale));
		let best = null;
		let bestDist = radius;
		mapData.stations.forEach(st => {
			const p = worldToCanvas(st.x, st.z);
			const d = Math.hypot(p.x - mx, p.y - my);
			if (d <= bestDist) { bestDist = d; best = st; }
		});
		return best;
	}

	// 依次选择起点 / 终点；两者都已选时把本次点击当作新的起点
	function handleStationPick(station) {
		if (!station) return;
		if (routeStartId === null) {
			routeStartId = station.id;
			routeFromMyLocation = false;
		} else if (routeEndId === null) {
			routeEndId = station.id;
		} else {
			routeStartId = station.id;
			routeEndId = null;
			routeFromMyLocation = false;
		}
		routeResult = null;
		activeRouteTab = 0;
		legExpandState = {};
		updateRoutePanel();
		render();
	}

	// ===== 路径查询：绘制 =====
	// 示意图：所有线路按相邻车站直线相连，线条更细
	function drawSchematicRoutes() {
		if (!mapData.routes) return;
		mapData.routes.forEach(route => {
			if (!route.stations || route.stations.length < 2) return;
			ctx.strokeStyle = intToRgba(route.color, 0.85);
			ctx.lineWidth = Math.max(1.5, 2 * Math.sqrt(scale));
			ctx.lineCap = 'round';
			ctx.lineJoin = 'round';
			const stations = route.stations;
			for (let i = 0; i < stations.length - 1; i++) {
				const a = stationMap[stations[i]];
				const b = stationMap[stations[i + 1]];
				if (!a || !b) continue;
				const pa = worldToCanvas(a.x, a.z);
				const pb = worldToCanvas(b.x, b.z);
				ctx.beginPath();
				ctx.moveTo(pa.x, pa.y);
				ctx.lineTo(pb.x, pb.y);
				ctx.stroke();
			}
		});
	}

	function drawRouteSelection() {
		if (routeStartId !== null) drawSelectionMarker(routeStartId, uiColors.start, t('route_start_tag'));
		if (routeEndId !== null) drawSelectionMarker(routeEndId, uiColors.end, t('route_end_tag'));
	}

	function drawSelectionMarker(stationId, color, label) {
		const st = stationMap[stationId];
		if (!st) return;
		const p = worldToCanvas(st.x, st.z);
		const base = routeMode ? Math.max(6, 8 * Math.sqrt(scale)) : Math.max(4, 6 * Math.sqrt(scale));
		const r = base + 5;
		ctx.beginPath();
		ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
		ctx.strokeStyle = color;
		ctx.lineWidth = 3;
		ctx.stroke();
		ctx.globalAlpha = 0.35;
		ctx.beginPath();
		ctx.arc(p.x, p.y, base, 0, Math.PI * 2);
		ctx.fillStyle = color;
		ctx.fill();
		ctx.globalAlpha = 1;
		// 文字标签
		ctx.font = canvasFont(12, 'bold');
		const bw = ctx.measureText(label).width + 12;
		const bh = 18;
		const bx = p.x - bw / 2;
		const by = p.y + r + 6;
		roundRect(ctx, bx, by, bw, bh, 4);
		ctx.fillStyle = color;
		ctx.fill();
		ctx.fillStyle = '#ffffff';
		ctx.textAlign = 'center';
		ctx.textBaseline = 'middle';
		ctx.fillText(label, p.x, by + bh / 2 + 1);
	}

	// ===== 路径查询：步行指引绘制 =====
	// 「我的位置」标记 + 到起点站的步行虚线（带方位箭头）；换乘中的步行段同样处理。
	function drawWalkNavigation() {
		// 换乘步行段
		const opt = (routeResult && routeResult.options && routeResult.options.length > 0)
			? (routeResult.options[activeRouteTab] || routeResult.options[0]) : null;
		if (opt && opt.steps) {
			opt.steps.forEach(step => {
				if (step.type !== 'walk') return;
				const a = stationMap[step.fromStation];
				const b = stationMap[step.toStation];
				if (a && b) drawWalkLine(a, b, false);
			});
		}
		// 我的位置 → 起点站
		if (myLocation) {
			const st = routeStartId !== null ? stationMap[routeStartId] : null;
			if (st) drawWalkLine(myLocation, st, true);
			drawMyLocationMarker();
		}
	}

	// 从 from 到 to 画一条步行虚线，并在中点标出朝向与方位
	function drawWalkLine(from, to, primary) {
		const pa = worldToCanvas(from.x, from.z);
		const pb = worldToCanvas(to.x, to.z);
		if (Math.hypot(pb.x - pa.x, pb.y - pa.y) < 2) return;
		ctx.save();
		ctx.strokeStyle = primary ? '#7ee787' : 'rgba(200, 205, 215, 0.9)';
		ctx.lineWidth = primary ? 2.5 : 2;
		ctx.setLineDash(primary ? [9, 6] : [6, 5]);
		ctx.beginPath();
		ctx.moveTo(pa.x, pa.y);
		ctx.lineTo(pb.x, pb.y);
		ctx.stroke();
		ctx.setLineDash([]);
		// 中点箭头：指向 to
		const mid = { x: (pa.x + pb.x) / 2, y: (pa.y + pb.y) / 2 };
		const ang = Math.atan2(pb.y - pa.y, pb.x - pa.x);
		const size = 9;
		ctx.fillStyle = primary ? '#7ee787' : 'rgba(200, 205, 215, 0.95)';
		ctx.beginPath();
		ctx.moveTo(mid.x + Math.cos(ang) * size, mid.y + Math.sin(ang) * size);
		ctx.lineTo(mid.x + Math.cos(ang + 2.5) * size, mid.y + Math.sin(ang + 2.5) * size);
		ctx.lineTo(mid.x + Math.cos(ang - 2.5) * size, mid.y + Math.sin(ang - 2.5) * size);
		ctx.closePath();
		ctx.fill();
		// 方位文字
		const dirKey = walkDirectionKey(from, to);
		if (dirKey) {
			const label = t('route_walk_towards', { dir: t(dirKey) });
			ctx.font = canvasFont(12, 'bold');
			const w = ctx.measureText(label).width + 12;
			const h = 20;
			roundRect(ctx, mid.x - w / 2, mid.y - h / 2, w, h, 4);
			ctx.fillStyle = 'rgba(20, 20, 40, 0.9)';
			ctx.fill();
			ctx.strokeStyle = primary ? '#7ee787' : 'rgba(200, 205, 215, 0.7)';
			ctx.lineWidth = 1;
			ctx.stroke();
			ctx.fillStyle = '#ffffff';
			ctx.textAlign = 'center';
			ctx.textBaseline = 'middle';
			ctx.fillText(label, mid.x, mid.y + 1);
		}
		ctx.restore();
	}

	// 我在地图上的实际位置：绿色圆点 + 白环 + 标签
	function drawMyLocationMarker() {
		const p = worldToCanvas(myLocation.x, myLocation.z);
		ctx.save();
		ctx.beginPath();
		ctx.arc(p.x, p.y, 7, 0, Math.PI * 2);
		ctx.fillStyle = '#50fa7b';
		ctx.fill();
		ctx.lineWidth = 2.5;
		ctx.strokeStyle = '#ffffff';
		ctx.stroke();
		const label = t('my_location');
		ctx.font = canvasFont(12, 'bold');
		const w = ctx.measureText(label).width + 12;
		const h = 20;
		roundRect(ctx, p.x - w / 2, p.y + 12, w, h, 4);
		ctx.fillStyle = '#50fa7b';
		ctx.fill();
		ctx.fillStyle = '#0b2a16';
		ctx.textAlign = 'center';
		ctx.textBaseline = 'middle';
		ctx.fillText(label, p.x, p.y + 12 + h / 2 + 1);
		ctx.restore();
	}

	routeBtn.addEventListener('click', () => { setRouteMode(!routeMode); });

	routeCloseBtn.addEventListener('click', () => { setRouteMode(false); });

	routeSearchBtn.addEventListener('click', () => {
		computeRoutes();
		updateRoutePanel();
	});

	routeClearBtn.addEventListener('click', () => {
		routeStartId = null;
		routeEndId = null;
		routeResult = null;
		routeFromMyLocation = false;
		startWalkInfo = null;
		activeRouteTab = 0;
		legExpandState = {};
		updateRoutePanel();
		render();
	});

	// 「我的位置」：识别玩家并以最近车站为起点
	routeMyLocationBtn.addEventListener('click', () => { useMyLocation(); });

	// 左上角用户卡片：点击展开/收起子菜单
	userCardMain.addEventListener('click', e => {
		e.stopPropagation();
		const open = userMenu.style.display === 'block';
		userMenu.style.display = open ? 'none' : 'block';
	});
	document.addEventListener('click', e => {
		if (userMenu.style.display === 'block' && !userCard.contains(e.target)) {
			userMenu.style.display = 'none';
		}
	});
	userTripsBtn.addEventListener('click', () => {
		userMenu.style.display = 'none';
		tripsTitle.textContent = t('trips_title');
		tripsClose.textContent = t('trips_close');
		tripsDialog.style.display = 'flex';
		loadTrips();
	});
	tripsClose.addEventListener('click', () => { tripsDialog.style.display = 'none'; });
	tripsOverlay.addEventListener('click', () => { tripsDialog.style.display = 'none'; });

	// 删除确认框
	confirmOk.addEventListener('click', () => {
		const fn = pendingConfirm;
		pendingConfirm = null;
		confirmDialog.style.display = 'none';
		if (fn) fn();
	});
	confirmCancel.addEventListener('click', () => {
		pendingConfirm = null;
		confirmDialog.style.display = 'none';
	});
	confirmOverlay.addEventListener('click', () => {
		pendingConfirm = null;
		confirmDialog.style.display = 'none';
	});

	// 纪念票根
	ticketSave.addEventListener('click', () => { saveTicket(); });
	ticketPrint.addEventListener('click', () => { printTicket(); });
	ticketCancel.addEventListener('click', () => { ticketDialog.style.display = 'none'; });
	ticketOverlay.addEventListener('click', () => { ticketDialog.style.display = 'none'; });

	// ===== 左上角线路一览 =====
	// 每条线路：左边一条标识色直线，右边三行（主名称 / 外语副名 / 起点站~终点站），
	// 后两行字号更小；网格固定四列，每排最多四个。
	function renderLineList() {
		const routes = mapData.routes || [];
		// 线路没变化就不重画（数据每秒刷新，但线网是静态的）
		const signature = routes.map(r => r.id + ':' + r.name).join(',');
		if (signature === lineListSignature) return;
		lineListSignature = signature;
		// 每排最多四个：列数按线路条数收缩，线路少时面板不会空出一大片
		lineListEl.style.gridTemplateColumns =
			'repeat(' + Math.min(4, Math.max(1, routes.length)) + ', minmax(0, max-content))';

		lineListEl.innerHTML = routes.map(route => {
			const n = splitName(route.name);
			const ids = route.stations || [];
			const from = ids.length ? splitName((stationMap[ids[0]] || {}).name).main : '';
			const to = ids.length ? splitName((stationMap[ids[ids.length - 1]] || {}).name).main : '';
			const ends = from && to ? from + '~' + to : '';
			return '<div class="line-item" title="' + escapeHtml(n.main) + '">' +
				'<span class="line-item-bar" style="background:' + intToRgba(route.color, 1) + '"></span>' +
				'<span class="line-item-text">' +
					'<span class="line-item-name">' + escapeHtml(n.main) + '</span>' +
					(n.trans ? '<span class="line-item-sub">' + escapeHtml(n.trans) + '</span>' : '') +
					(ends ? '<span class="line-item-ends">' + escapeHtml(ends) + '</span>' : '') +
				'</span>' +
			'</div>';
		}).join('');
	}

	// ===== 搜索 / 右侧详情侧边栏 =====
	/** 侧边栏里显示的名称：按 detailLang 选，没有译名时回退本名 */
	function detailNameFrom(name) {
		const n = splitName(name);
		return (detailLang === 'en' && n.trans) ? n.trans : n.main;
	}

	function updateSearchUiText() {
		searchType.options[0].textContent = t('search_type_station');
		searchType.options[1].textContent = t('search_type_route');
		searchInput.placeholder = searchType.value === 'route'
			? t('search_placeholder_route') : t('search_placeholder_station');
		searchType.title = t('search_type_station') + ' / ' + t('search_type_route');
		detailLangBtn.title = t('detail_lang_title');
		detailCloseBtn.title = t('route_close');
	}

	/** 经过该车站的所有线路 */
	function stationLines(stationId) {
		const out = [];
		(mapData.routes || []).forEach(route => {
			if (route.stations && route.stations.indexOf(stationId) !== -1) out.push(route);
		});
		return out;
	}

	/** 线路全程耗时（分钟）：总长度按平均运营速度走 + 每站停站时间 */
	function routeTravelMinutes(route) {
		const st = route.stations || [];
		const paths = route.paths || [];
		let dist = 0;
		for (let i = 0; i < st.length - 1; i++) {
			dist += segmentLength(paths[i], stationMap[st[i]], stationMap[st[i + 1]]);
		}
		const sec = dist / ROUTE_AVG_SPEED_PER_SEC + Math.max(0, st.length - 1) * ROUTE_DWELL_SEC;
		return Math.max(1, Math.round(sec / 60));
	}

	/** 线路占用的世界坐标范围（用于把视图对准它） */
	function routeBounds(route) {
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
		(route.stations || []).forEach(sid => {
			const st = stationMap[sid];
			if (!st) return;
			minX = Math.min(minX, st.x); maxX = Math.max(maxX, st.x);
			minZ = Math.min(minZ, st.z); maxZ = Math.max(maxZ, st.z);
		});
		(route.paths || []).forEach(seg => {
			if (!seg) return;
			seg.forEach(pt => {
				minX = Math.min(minX, pt.x); maxX = Math.max(maxX, pt.x);
				minZ = Math.min(minZ, pt.z); maxZ = Math.max(maxZ, pt.z);
			});
		});
		return minX <= maxX ? { minX: minX, minZ: minZ, maxX: maxX, maxZ: maxZ } : null;
	}

	/** 把给定点移到视图中央（不改缩放，避免点一下车站就把视图猛拉一下） */
	function centerOn(x, z) {
		offsetX = canvas.width / 2 - x * scale;
		offsetY = canvas.height / 2 - z * scale;
		viewInitialized = true;
	}

	/** 把视图对准给定范围 */
	function focusBounds(minX, minZ, maxX, maxZ) {
		const pad = 100;
		const w = Math.max(maxX - minX, 1);
		const h = Math.max(maxZ - minZ, 1);
		const fit = Math.min((canvas.width - pad * 2) / w, (canvas.height - pad * 2) / h);
		scale = Math.max(0.05, Math.min(fit, 3));
		offsetX = canvas.width / 2 - (minX + maxX) / 2 * scale;
		offsetY = canvas.height / 2 - (minZ + maxZ) / 2 * scale;
		viewInitialized = true;
	}

	function openStationDetail(station) {
		if (!station) return;
		detailState = { type: 'station', id: station.id };
		detailLang = currentLang;
		highlight = { type: 'station', id: station.id };
		renderDetailPanel();
		detailPanel.style.display = 'flex';
		centerOn(station.x, station.z);
		render();
	}

	function openRouteDetail(route) {
		if (!route) return;
		detailState = { type: 'route', id: route.id };
		detailLang = currentLang;
		highlight = { type: 'route', id: route.id };
		renderDetailPanel();
		detailPanel.style.display = 'flex';
		const b = routeBounds(route);
		if (b) focusBounds(b.minX, b.minZ, b.maxX, b.maxZ);
		render();
	}

	function closeDetailPanel() {
		detailState = null;
		highlight = null;
		detailPanel.style.display = 'none';
		render();
	}

	function renderDetailPanel() {
		if (!detailState) {
			detailPanel.style.display = 'none';
			return;
		}
		if (detailState.type === 'route') {
			renderRouteDetail();
		} else {
			renderStationDetail();
		}
		detailLangBtn.textContent = detailLang === 'zh' ? 'EN' : '中';
	}

	function renderStationDetail() {
		const st = stationMap[detailState.id];
		if (!st) { closeDetailPanel(); return; }
		const n = splitName(st.name);
		const primary = detailNameFrom(st.name);
		const secondary = detailLang === 'en' ? n.main : n.trans;
		detailTitle.textContent = primary;
		detailTitle.style.color = intToRgba(st.color, 1);

		let html = '';
		if (secondary && secondary !== primary) {
			html += '<div class="detail-subtitle">' + escapeHtml(secondary) + '</div>';
		}
		html += '<div class="detail-row"><span class="detail-label">' + escapeHtml(t('detail_coord')) +
			'</span><span>X ' + Math.round(st.x) + '　Z ' + Math.round(st.z) + '</span></div>';
		html += '<div class="detail-row"><span class="detail-label">' + escapeHtml(t('detail_lines')) + '</span></div>';
		const lines = stationLines(st.id);
		if (lines.length === 0) {
			html += '<div class="detail-row"><span>' + escapeHtml(t('detail_no_lines')) + '</span></div>';
		} else {
			html += '<div class="detail-line-tags">' + lines.map(r => {
				const color = intToRgba(r.color, 1);
				return '<span class="detail-line-tag" data-route="' + r.id + '" style="border-color:' + color +
					';color:' + color + '">' + escapeHtml(detailNameFrom(r.name)) + '</span>';
			}).join('') + '</div>';
		}
		detailBody.innerHTML = html;
		// 点线路标签直接切到该线路的信息
		detailBody.querySelectorAll('.detail-line-tag').forEach(el => {
			el.addEventListener('click', () => {
				openRouteDetail((mapData.routes || []).find(r => r.id === Number(el.getAttribute('data-route'))));
			});
		});
	}

	function renderRouteDetail() {
		const route = (mapData.routes || []).find(r => r.id === detailState.id);
		if (!route) { closeDetailPanel(); return; }
		const n = splitName(route.name);
		const primary = detailNameFrom(route.name);
		const secondary = detailLang === 'en' ? n.main : n.trans;
		detailTitle.textContent = primary;
		detailTitle.style.color = intToRgba(route.color, 1);

		let html = '';
		if (secondary && secondary !== primary) {
			html += '<div class="detail-subtitle">' + escapeHtml(secondary) + '</div>';
		}
		html += '<div class="detail-headway">' + escapeHtml(route.headway > 0
			? t('detail_headway', { n: route.headway }) : t('detail_headway_unknown')) + '</div>';

		const color = intToRgba(route.color, 1);
		html += '<div class="detail-stations" style="--st-line:' + color + '">';
		(route.stations || []).forEach(sid => {
			const st = stationMap[sid];
			html += '<div class="detail-station' + (st && st.lines >= 2 ? ' interchange' : '') + '">' +
				'<span class="detail-station-dot"></span>' +
				'<span class="detail-station-name">' +
					escapeHtml(st ? detailNameFrom(st.name) : String(sid)) + '</span>' +
			'</div>';
		});
		html += '</div>';
		html += '<div class="detail-footer">' +
			escapeHtml(t('detail_full_trip', { n: routeTravelMinutes(route) })) + '</div>';
		detailBody.innerHTML = html;
	}

	// ===== 地图高亮 =====
	function drawHighlight() {
		if (!highlight) return;
		if (highlight.type === 'route') {
			const route = (mapData.routes || []).find(r => r.id === highlight.id);
			if (route) highlightRoute(route);
		} else {
			const st = stationMap[highlight.id];
			if (st) highlightStation(st);
		}
	}

	/** 线路高亮：金色荧光垫底，再用线路本色描边并带一圈本色荧光 */
	function highlightRoute(route) {
		const stations = route.stations || [];
		const paths = route.paths || [];
		const base = Math.max(2, 4 * Math.sqrt(scale));
		const stroke = (width, style) => {
			ctx.strokeStyle = style;
			ctx.lineWidth = width;
			ctx.lineCap = 'round';
			ctx.lineJoin = 'round';
			for (let i = 0; i < stations.length - 1; i++) {
				const seg = paths[i];
				ctx.beginPath();
				if (seg && seg.length >= 2) {
					seg.forEach((pt, k) => {
						const p = worldToCanvas(pt.x, pt.z);
						if (k === 0) ctx.moveTo(p.x, p.y);
						else ctx.lineTo(p.x, p.y);
					});
				} else {
					const a = stationMap[stations[i]];
					const b = stationMap[stations[i + 1]];
					if (!a || !b) continue;
					const pa = worldToCanvas(a.x, a.z);
					const pb = worldToCanvas(b.x, b.z);
					ctx.moveTo(pa.x, pa.y);
					ctx.lineTo(pb.x, pb.y);
				}
				ctx.stroke();
			}
		};
		ctx.save();
		// 荧光：金色光晕垫底，本色线再带一圈本色发光
		ctx.shadowColor = 'rgba(255, 208, 64, 0.95)';
		ctx.shadowBlur = Math.max(16, base * 5);
		stroke(base * 3, 'rgba(255, 208, 64, 0.35)');
		ctx.shadowColor = intToRgba(route.color, 0.95);
		ctx.shadowBlur = Math.max(10, base * 3);
		stroke(base + 1.5, intToRgba(route.color, 1));
		ctx.restore();
	}

	/** 车站高亮：金色荧光双环 */
	function highlightStation(st) {
		const p = worldToCanvas(st.x, st.z);
		const r = Math.max(6, 8 * Math.sqrt(scale));
		ctx.save();
		// 荧光：给环描边加一层发光
		ctx.shadowColor = 'rgba(255, 216, 80, 0.95)';
		ctx.shadowBlur = Math.max(14, r * 2.2);
		ctx.beginPath();
		ctx.arc(p.x, p.y, r + 5, 0, Math.PI * 2);
		ctx.strokeStyle = 'rgba(255, 208, 64, 0.45)';
		ctx.lineWidth = 6;
		ctx.stroke();
		ctx.beginPath();
		ctx.arc(p.x, p.y, r + 11, 0, Math.PI * 2);
		ctx.strokeStyle = '#ffd040';
		ctx.lineWidth = 2.5;
		ctx.stroke();
		ctx.restore();
	}

	// ===== 搜索框 =====
	/** 按当前「搜车站 / 搜线路」与关键词挑出候选词 */
	function searchTargets() {
		const q = searchInput.value.trim().toLowerCase();
		if (!q) return [];
		const type = searchType.value;
		const out = [];
		const source = type === 'route' ? (mapData.routes || []) : (mapData.stations || []);
		source.forEach(item => {
			const n = splitName(item.name);
			if (n.main.toLowerCase().indexOf(q) === -1 && n.trans.toLowerCase().indexOf(q) === -1) return;
			out.push({
				type: type === 'route' ? 'route' : 'station',
				id: item.id,
				color: item.color,
				name: item.name,
				lines: item.lines || 0,
				stopCount: (item.stations || []).length
			});
		});
		return out.slice(0, 30);
	}

	function renderSearchResults() {
		searchMatches = searchTargets();
		searchActiveIndex = searchMatches.length ? 0 : -1;
		if (searchMatches.length === 0) {
			if (!searchInput.value.trim()) {
				searchResults.style.display = 'none';
				return;
			}
			searchResults.innerHTML = '<div class="search-empty">' + escapeHtml(t('search_no_result')) + '</div>';
			searchResults.style.display = 'block';
			return;
		}
		searchResults.innerHTML = searchMatches.map((it, i) => {
			const sub = it.type === 'route'
				? t('search_stop_count', { n: it.stopCount })
				: (it.lines >= 2 ? t('interchange') : '');
			return '<div class="search-item' + (i === searchActiveIndex ? ' active' : '') +
				'" data-index="' + i + '">' +
				'<span class="search-item-dot" style="background:' + intToRgba(it.color, 1) + '"></span>' +
				'<span class="search-item-name">' + escapeHtml(detailNameFrom(it.name)) + '</span>' +
				(sub ? '<span class="search-item-sub">' + escapeHtml(sub) + '</span>' : '') +
			'</div>';
		}).join('');
		searchResults.style.display = 'block';
		searchResults.querySelectorAll('.search-item').forEach(el => {
			// 用 mousedown 抢在输入框失焦之前选中，避免下拉框先被收起
			el.addEventListener('mousedown', e => {
				e.preventDefault();
				selectSearchItem(Number(el.getAttribute('data-index')));
			});
		});
	}

	/** 高亮候选词（键盘上下键用），不重排列表 */
	function setSearchActive(index) {
		if (index < 0 || index >= searchMatches.length) return;
		searchActiveIndex = index;
		const items = searchResults.querySelectorAll('.search-item');
		items.forEach((el, i) => el.classList.toggle('active', i === index));
		if (items[index]) items[index].scrollIntoView({ block: 'nearest' });
	}

	function selectSearchItem(index) {
		const item = searchMatches[index];
		searchResults.style.display = 'none';
		if (!item) return;
		if (item.type === 'route') {
			openRouteDetail((mapData.routes || []).find(r => r.id === item.id));
		} else {
			openStationDetail(stationMap[item.id]);
		}
	}

	searchInput.addEventListener('input', renderSearchResults);
	searchInput.addEventListener('focus', renderSearchResults);
	searchType.addEventListener('change', () => {
		updateSearchUiText();
		renderSearchResults();
	});
	searchInput.addEventListener('keydown', e => {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			setSearchActive(Math.min(searchActiveIndex + 1, searchMatches.length - 1));
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			setSearchActive(Math.max(searchActiveIndex - 1, 0));
		} else if (e.key === 'Enter') {
			e.preventDefault();
			selectSearchItem(searchActiveIndex >= 0 ? searchActiveIndex : 0);
		} else if (e.key === 'Escape') {
			searchResults.style.display = 'none';
		}
	});
	document.addEventListener('click', e => {
		if (searchResults.style.display === 'block' && !searchBox.contains(e.target)) {
			searchResults.style.display = 'none';
		}
	});
	detailLangBtn.addEventListener('click', () => {
		detailLang = detailLang === 'zh' ? 'en' : 'zh';
		renderDetailPanel();
	});
	detailCloseBtn.addEventListener('click', () => { closeDetailPanel(); });

	// ===== 交互 =====
	function setupInteraction() {
		canvas.addEventListener('mousedown', e => {
			isDragging = true;
			lastMouseX = e.clientX;
			lastMouseY = e.clientY;
			mouseDownX = e.clientX;
			mouseDownY = e.clientY;
		});
		window.addEventListener('mousemove', e => {
			lastClientX = e.clientX;
			lastClientY = e.clientY;
			if (isDragging) {
				offsetX += e.clientX - lastMouseX;
				offsetY += e.clientY - lastMouseY;
				lastMouseX = e.clientX;
				lastMouseY = e.clientY;
				render();
			} else {
				updateTooltip(e.clientX, e.clientY);
			}
		});
		window.addEventListener('mouseup', e => {
			// 位移小于 4px 才算「点击」，避免与拖拽冲突
			const clicked = isDragging && e.target === canvas &&
				Math.hypot(e.clientX - mouseDownX, e.clientY - mouseDownY) < 4;
			if (clicked) {
				const rect = canvas.getBoundingClientRect();
				const station = getStationAt(e.clientX - rect.left, e.clientY - rect.top);
				if (routeMode) {
					handleStationPick(station);
				} else if (station) {
					// 非路径查询模式下点车站：右侧弹出该站详情
					openStationDetail(station);
				} else {
					closeDetailPanel();
				}
			}
			isDragging = false;
		});
		canvas.addEventListener('wheel', e => {
			e.preventDefault();
			const rect = canvas.getBoundingClientRect();
			const mx = e.clientX - rect.left;
			const my = e.clientY - rect.top;
			const world = canvasToWorld(mx, my);
			const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
			scale *= factor;
			if (scale < 0.01) scale = 0.01;
			if (scale > 50) scale = 50;
			offsetX = mx - world.x * scale;
			offsetY = my - world.z * scale;
			render();
		}, { passive: false });
		document.getElementById('zoomIn').addEventListener('click', () => { scale *= 1.3; render(); });
		document.getElementById('zoomOut').addEventListener('click', () => { scale /= 1.3; render(); });
		document.getElementById('reset').addEventListener('click', () => {
			viewInitialized = false;
			fitView();
			render();
		});
	}

	// ===== Canvas 尺寸 =====
	function resizeCanvas() {
		canvas.width = window.innerWidth;
		canvas.height = window.innerHeight;
		render();
	}

	// ===== 初始化 =====
	function refreshData() {
		Promise.all([loadMapData(), loadPlayers()])
			.then(() => {
				render();
				updateTooltip(lastClientX, lastClientY);
				setTimeout(refreshData, 1000);
			})
			.catch(() => setTimeout(refreshData, 1000));
	}

	function init() {
		// 初始语言
		document.documentElement.lang = 'zh-CN';
		document.title = t('page_title');
		langBtn.textContent = t('btn_lang');
		langBtn.title = t('btn_lang_title');
		themeBtn.title = t('dark_mode');
		document.getElementById('zoomIn').title = t('zoom_in');
		document.getElementById('zoomOut').title = t('zoom_out');
		document.getElementById('reset').title = t('reset_view');
		exportBtn.title = t('export_btn_title');
		routeBtn.title = t('route_btn_title');
		controlsToggle.title = t('controls_toggle');
		fontSelect.title = t('font_title');
		updateSearchUiText();
		fillFontSelect();
		updateDepotBtn();
		updateLegend();
		statusEl.textContent = t('loading');
		refreshUiColors();

		resizeCanvas();
		setupInteraction();
		window.addEventListener('resize', resizeCanvas);
		refreshData();
		// 拉取自研世界地图参数（失败会自动重试），拿到后底图才会出现
		loadWorldMapSettings();
		// 识别当前访问者是不是游戏内玩家（是则左上角显示头像与用户名）
		fetchWhoAmI();
		// 之后每 2 秒刷新一次位置，让「我的位置」跟着游戏内实际坐标走
		setInterval(refreshMyLocation, MY_LOCATION_REFRESH_MS);
	}

	init();
})();