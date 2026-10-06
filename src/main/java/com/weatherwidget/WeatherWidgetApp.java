package com.weatherwidget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.AWTException;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class WeatherWidgetApp extends Application {

    // 行高与间距（轮播按此步进）
    private static final double ROW_HEIGHT = 34;
    private static final double ROW_GAP = 8;
    private static final double ROW_STEP = ROW_HEIGHT + ROW_GAP;
    // 轮播时仅显示一行
    private static final int VISIBLE_ROWS = 1;
    // 轮播数据：未来三个小时（不含当前小时）
    private static final int FORECAST_HOURS = 3;

    // 突变提醒观察窗口：未来多少小时内检测天气突变
    private static final int ALERT_WINDOW_HOURS = 24;

    // 轮播时序：普通行停留时间、"现在"行停留为其两倍、总轮播时长
    private static final double CAROUSEL_PAUSE_SECONDS = 2.2;
    private static final long CAROUSEL_RUN_MILLIS = 30_000;

    private static final String ROOT_EFFECT =
            "-fx-background-radius: 16;" +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 12, 0, 0, 4);";

    // 默认坐标：柏林（首次启动无默认定位时会被自动定位覆盖）
    private static double latitude = 39.9075;
    private static double longitude = 116.3972;
    private static String locationName = "北京";

    // 当前运行中的实例，供设置窗口保存后触发刷新
    private static WeatherWidgetApp instance;

    // 默认定位持久化
    private static final Preferences PREFS = Preferences.userNodeForPackage(WeatherWidgetApp.class);
    private static final String KEY_LAT = "default.lat";
    private static final String KEY_LON = "default.lon";
    private static final String KEY_NAME = "default.name";
    private static final String KEY_SHOW_COORDS = "display.showCoordinates";
    private static final String KEY_AUTO_REGION = "display.autoAdminRegion";
    private static final String KEY_WIN_X = "window.x";
    private static final String KEY_WIN_Y = "window.y";

    // 最近定位：最多保存 5 条，键为 recent.<i>.name/lat/lon
    private static final String KEY_RECENT_PREFIX = "recent.";
    private static final int RECENT_MAX = 5;

    private Stage primaryStage;
    private TrayIcon trayIcon;

    // 每 15 分钟自动刷新
    private Timeline autoRefreshTimeline;

    // 是否已成功获取过一次数据（网络失败时用于沿用上次数据）
    private boolean hasData = false;

    // 提醒状态：启动简报每次启动只发一次；预警按事件文案去重，避免每 15 分钟重复提醒
    private boolean startupSummaryShown = false;
    private String lastAlertKey = "";

    private VBox rootBox;
    private VBox forecastContainer;
    private Pane forecastViewport;
    private Label currentTempLabel;
    private Label currentEmojiLabel;
    private Label locationLabel;
    private Label detailLabel;

    // 轮播状态
    private Animation carouselPause;
    private Animation carouselSlide;
    private boolean carouselRunning = false;
    private long carouselStartNanos = 0;
    private List<HourlyData> lastForecasts = List.of();

    // 文字颜色（随背景深浅自动切换）
    private Color primaryText = Color.WHITE;
    private Color secondaryText = Color.web("#9CA3AF");
    private Color accentText = Color.web("#60A5FA");
    private String rowHoverColor = "rgba(255,255,255,0.08)";

    private double xOffset = 0;
    private double yOffset = 0;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void setLocation(double lat, double lon) {
        setLocation(lat, lon, String.format("%.2f, %.2f", lat, lon));
    }

    public static void setLocation(double lat, double lon, String name) {
        latitude = lat;
        longitude = lon;
        locationName = name;
    }

    public static double getLatitude() { return latitude; }
    public static double getLongitude() { return longitude; }
    public static String getLocationName() { return locationName; }

    // ===== 默认定位持久化 =====

    private static boolean hasDefaultLocation() {
        return !PREFS.get(KEY_NAME, "").isEmpty();
    }

    private static void loadDefaultLocation() {
        latitude = PREFS.getDouble(KEY_LAT, latitude);
        longitude = PREFS.getDouble(KEY_LON, longitude);
        locationName = PREFS.get(KEY_NAME, locationName);
    }

    private static void saveDefaultLocation() {
        PREFS.putDouble(KEY_LAT, latitude);
        PREFS.putDouble(KEY_LON, longitude);
        PREFS.put(KEY_NAME, locationName);
        try {
            PREFS.flush();
        } catch (BackingStoreException ignored) {
            // 持久化失败不影响运行
        }
    }

    /** 是否在城市名称后显示坐标 */
    public static boolean isShowCoordinates() {
        return PREFS.getBoolean(KEY_SHOW_COORDS, true);
    }

    public static void setShowCoordinates(boolean show) {
        PREFS.putBoolean(KEY_SHOW_COORDS, show);
        try {
            PREFS.flush();
        } catch (BackingStoreException ignored) {
            // 持久化失败不影响运行
        }
    }

    /** 坐标为来源的定位，是否自动转换为所在行政区（默认是） */
    public static boolean isAutoAdminRegion() {
        return PREFS.getBoolean(KEY_AUTO_REGION, true);
    }

    public static void setAutoAdminRegion(boolean auto) {
        PREFS.putBoolean(KEY_AUTO_REGION, auto);
        try {
            PREFS.flush();
        } catch (BackingStoreException ignored) {
            // 持久化失败不影响运行
        }
    }

    // ===== 最近定位（最多 5 条） =====

    /** 读取最近定位列表（按由新到旧排序） */
    public static List<LocationService.Location> getRecentLocations() {
        List<LocationService.Location> list = new ArrayList<>();
        for (int i = 0; i < RECENT_MAX; i++) {
            String name = PREFS.get(KEY_RECENT_PREFIX + i + ".name", "");
            if (name.isEmpty()) {
                continue;
            }
            double lat = PREFS.getDouble(KEY_RECENT_PREFIX + i + ".lat", Double.NaN);
            double lon = PREFS.getDouble(KEY_RECENT_PREFIX + i + ".lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                continue;
            }
            list.add(new LocationService.Location(lat, lon, name));
        }
        return list;
    }

    /** 记录一次定位结果：按坐标去重、置顶并截断为最多 5 条 */
    private static void addRecentLocation(double lat, double lon, String name) {
        List<LocationService.Location> list = getRecentLocations();
        list.removeIf(l -> Math.abs(l.latitude() - lat) < 1e-6
                && Math.abs(l.longitude() - lon) < 1e-6);
        list.add(0, new LocationService.Location(lat, lon, name));
        while (list.size() > RECENT_MAX) {
            list.remove(list.size() - 1);
        }

        for (int i = 0; i < RECENT_MAX; i++) {
            if (i < list.size()) {
                LocationService.Location l = list.get(i);
                PREFS.put(KEY_RECENT_PREFIX + i + ".name", l.name());
                PREFS.putDouble(KEY_RECENT_PREFIX + i + ".lat", l.latitude());
                PREFS.putDouble(KEY_RECENT_PREFIX + i + ".lon", l.longitude());
            } else {
                PREFS.remove(KEY_RECENT_PREFIX + i + ".name");
                PREFS.remove(KEY_RECENT_PREFIX + i + ".lat");
                PREFS.remove(KEY_RECENT_PREFIX + i + ".lon");
            }
        }
        try {
            PREFS.flush();
        } catch (BackingStoreException ignored) {
            // 持久化失败不影响运行
        }
    }

    /**
     * 应用新的定位：更新内存定位、保存为默认、记录最近定位、刷新标签与天气数据。
     * 若定位名称为坐标串，则后台解析行政区：最近定位尽量显示行政区；
     * 顶部栏位是否同步更新取决于"坐标自动转换为行政区"设置（默认开启）。
     */
    public static void applyLocation(double lat, double lon, String name) {
        setLocation(lat, lon, name);
        saveDefaultLocation();
        addRecentLocation(lat, lon, name);
        refresh();
        if (WeatherLogic.isCoordinateName(name)) {
            resolveAdminRegionAsync(lat, lon);
        }
    }

    /** 后台把坐标解析为行政区名称：最近定位始终更新，顶部栏位按设置决定是否更新 */
    private static void resolveAdminRegionAsync(double lat, double lon) {
        new Thread(() -> {
            String admin = LocationService.reverseGeocodeAdmin(lat, lon);
            if (admin == null) {
                return;
            }
            Platform.runLater(() -> {
                // 最近定位尽量显示为行政区（按坐标去重，替换原坐标记录）
                addRecentLocation(lat, lon, admin);
                if (isAutoAdminRegion()) {
                    setLocation(lat, lon, admin);
                    saveDefaultLocation();
                    updateLocationLabel();
                }
            });
        }).start();
    }

    /** 仅在主窗口 UI 就绪时更新位置标签 */
    private static void updateLocationLabel() {
        if (instance != null && instance.locationLabel != null) {
            instance.locationLabel.setText(locationLabelText());
        }
    }

    /** 顶部位置标签文本：关闭坐标显示时，去掉名称中的坐标部分 */
    private static String locationLabelText() {
        return WeatherLogic.formatLocationLabel(locationName, latitude, longitude, isShowCoordinates());
    }

    /** 坐标变更后刷新主窗口：更新位置标签并重新拉取天气数据 */
    public static void refresh() {
        if (instance == null) {
            return;
        }
        instance.locationLabel.setText(locationLabelText());
        new Thread(instance::fetchWeatherData).start();
    }

    @Override
    public void start(Stage stage) {
        instance = this;
        primaryStage = stage;

        boolean hasDefault = hasDefaultLocation();
        if (hasDefault) {
            loadDefaultLocation();
            // 默认定位为坐标且开启了行政区转换时，启动后异步解析为行政区
            if (isAutoAdminRegion() && WeatherLogic.isCoordinateName(locationName)) {
                resolveAdminRegionAsync(latitude, longitude);
            }
        } else {
            // 默认定位为空：启动后异步自动定位一次并保存为默认
            locationName = "定位中…";
        }

        // ===== 1. 窗口基础配置 =====
        stage.initStyle(StageStyle.TRANSPARENT);
        // 置底：不置顶，允许被其他窗口遮挡
        stage.setAlwaysOnTop(false);

        // ===== 2. 根布局 =====
        rootBox = new VBox(12);
        rootBox.setPadding(new Insets(16));
        rootBox.setAlignment(Pos.TOP_LEFT);
        applyBackground(0);

        // ===== 3. 顶部区域：当前天气 + 温度 + 设置按钮 =====
        BorderPane headerPane = new BorderPane();

        currentEmojiLabel = new Label("🌡");
        currentEmojiLabel.setFont(Font.font("System", 40));

        // 左侧：当前位置 + 当前温度
        VBox currentInfo = new VBox(2);
        locationLabel = new Label(locationLabelText());
        locationLabel.setTextFill(Color.web("#d4def0"));
        locationLabel.setFont(Font.font("System", 11));

        currentTempLabel = new Label("--°");
        currentTempLabel.setTextFill(Color.WHITE);
        currentTempLabel.setFont(Font.font("System", FontWeight.BOLD, 36));

        currentInfo.getChildren().addAll(locationLabel, currentTempLabel);

        HBox currentGroup = new HBox(10);
        currentGroup.setAlignment(Pos.CENTER_LEFT);
        currentGroup.getChildren().addAll(currentEmojiLabel, currentInfo);

        // 右侧：设置按钮（鼠标悬浮时才显示）
        Button settingsBtn = new Button("⚙");
        settingsBtn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.1);" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 50%;" +
                        "-fx-min-width: 28; -fx-min-height: 28;" +
                        "-fx-max-width: 28; -fx-max-height: 28;" +
                        "-fx-cursor: hand;" +
                        "-fx-font-size: 14;"
        );
        settingsBtn.setVisible(false);
        settingsBtn.setOnAction(e -> SettingsWindow.open(stage));

        headerPane.setLeft(currentGroup);
        headerPane.setRight(settingsBtn);

        // ===== 4. 预报轮播视口 =====
        forecastContainer = new VBox(ROW_GAP);
        forecastContainer.setLayoutX(0);
        forecastContainer.setLayoutY(0);
        forecastContainer.prefWidthProperty().bind(rootBox.widthProperty().subtract(32));

        forecastViewport = new Pane(forecastContainer);
        forecastViewport.setMaxWidth(Double.MAX_VALUE);
        forecastViewport.setPrefHeight(ROW_HEIGHT * VISIBLE_ROWS + ROW_GAP * (VISIBLE_ROWS - 1));
        forecastViewport.setMinHeight(ROW_HEIGHT * VISIBLE_ROWS + ROW_GAP * (VISIBLE_ROWS - 1));

        // 裁剪，保证轮播时超出视口的部分不可见
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(forecastViewport.widthProperty());
        clip.heightProperty().bind(forecastViewport.heightProperty());
        forecastViewport.setClip(clip);

        // 点击轮播区域后重新开始轮播
        forecastViewport.setOnMouseClicked(e -> startCarousel());

        // ===== 4.5 天气详情行：体感 / 高低温 / 湿度 / 风速 =====
        detailLabel = new Label("");
        detailLabel.setTextFill(secondaryText);
        detailLabel.setFont(Font.font("System", 10));
        detailLabel.setMaxWidth(Double.MAX_VALUE);

        rootBox.getChildren().addAll(headerPane, detailLabel, forecastViewport);

        // ===== 5. 鼠标悬浮显示/隐藏设置按钮 =====
        rootBox.setOnMouseEntered(e -> settingsBtn.setVisible(true));
        rootBox.setOnMouseExited(e -> settingsBtn.setVisible(false));

        // ===== 6. 窗口拖拽 =====
        rootBox.setOnMousePressed(e -> {
            xOffset = e.getSceneX();
            yOffset = e.getSceneY();
        });
        rootBox.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - xOffset);
            stage.setY(e.getScreenY() - yOffset);
        });

        // ===== 6.5 右键菜单：刷新 / 自动定位 / 最近定位 / 设置 / 退出 =====
        ContextMenu contextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("🔄 刷新");
        refreshItem.setOnAction(e -> refresh());

        // 自动定位：按 IP 定位后更新天气数据
        MenuItem autoLocateItem = new MenuItem("📍 自动定位");
        autoLocateItem.setOnAction(e -> autoLocateFromMenu());

        // 最近定位：动态列出最多 5 条，点击即切换
        Menu recentMenu = new Menu("🕘 最近定位");
        MenuItem settingsItem = new MenuItem("⚙ 设置");
        settingsItem.setOnAction(e -> SettingsWindow.open(stage));
        MenuItem exitItem = new MenuItem("❌ 退出");
        exitItem.setOnAction(e -> Platform.exit());

        contextMenu.getItems().addAll(refreshItem, autoLocateItem, recentMenu,
                new SeparatorMenuItem(), settingsItem, exitItem);
        // 每次弹出前刷新最近定位列表
        contextMenu.setOnShowing(e -> rebuildRecentMenu(recentMenu));
        rootBox.setOnContextMenuRequested(e ->
                contextMenu.show(rootBox, e.getScreenX(), e.getScreenY()));

        // ===== 7. 场景设置 =====
        Scene scene = new Scene(rootBox, 320, 168);
        scene.setFill(Color.TRANSPARENT);

        stage.setScene(scene);
        stage.show();

        // 恢复上次窗口位置；无记录时默认定位到桌面右上角（避开任务栏）
        if (hasSavedWindowPosition()) {
            stage.setX(PREFS.getDouble(KEY_WIN_X, 0));
            stage.setY(PREFS.getDouble(KEY_WIN_Y, 0));
        } else {
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            stage.setX(vb.getMaxX() - 344);
            stage.setY(vb.getMinY() + 24);
        }

        // ===== 8. 每 15 分钟自动刷新 =====
        autoRefreshTimeline = new Timeline(
                new KeyFrame(Duration.minutes(15), e -> refresh())
        );
        autoRefreshTimeline.setCycleCount(Animation.INDEFINITE);
        autoRefreshTimeline.play();

        // ===== 8.5 系统托盘 =====
        setupTray();

        // ===== 9. 异步加载天气数据 =====
        if (hasDefault) {
            new Thread(this::fetchWeatherData).start();
        } else {
            autoLocateOnStartup();
        }
    }

    @Override
    public void stop() {
        // 组件关闭时保存当前定位与窗口位置，供下次启动使用
        saveDefaultLocation();
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
        }
        saveWindowPosition();
        removeTray();
    }

    /** 是否已保存过窗口位置 */
    private static boolean hasSavedWindowPosition() {
        return !Double.isNaN(PREFS.getDouble(KEY_WIN_X, Double.NaN))
                && !Double.isNaN(PREFS.getDouble(KEY_WIN_Y, Double.NaN));
    }

    /** 保存当前窗口位置（关闭时调用） */
    private void saveWindowPosition() {
        if (primaryStage == null) {
            return;
        }
        PREFS.putDouble(KEY_WIN_X, primaryStage.getX());
        PREFS.putDouble(KEY_WIN_Y, primaryStage.getY());
        try {
            PREFS.flush();
        } catch (BackingStoreException ignored) {
            // 持久化失败不影响运行
        }
    }

    /** 系统托盘：常驻图标，单击或菜单切换显示/隐藏，右键菜单可刷新/设置/退出 */
    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        // 隐藏窗口后不退出 JavaFX（由托盘常驻）
        Platform.setImplicitExit(false);

        PopupMenu popup = new PopupMenu();
        java.awt.MenuItem showHideItem = new java.awt.MenuItem("显示 / 隐藏");
        showHideItem.addActionListener(e -> Platform.runLater(this::toggleWindow));
        java.awt.MenuItem refreshItem = new java.awt.MenuItem("刷新");
        refreshItem.addActionListener(e -> Platform.runLater(WeatherWidgetApp::refresh));
        java.awt.MenuItem settingsItem = new java.awt.MenuItem("设置");
        settingsItem.addActionListener(e ->
                Platform.runLater(() -> SettingsWindow.open(primaryStage)));
        java.awt.MenuItem exitItem = new java.awt.MenuItem("退出");
        exitItem.addActionListener(e -> Platform.runLater(Platform::exit));
        popup.add(showHideItem);
        popup.add(refreshItem);
        popup.add(settingsItem);
        popup.addSeparator();
        popup.add(exitItem);

        trayIcon = new TrayIcon(createTrayImage(), "天气组件", popup);
        trayIcon.setImageAutoSize(true);
        // 双击托盘图标切换窗口显示
        trayIcon.addActionListener(e -> Platform.runLater(this::toggleWindow));

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    /** 显示 / 隐藏主窗口 */
    private void toggleWindow() {
        if (primaryStage == null) {
            return;
        }
        if (primaryStage.isShowing()) {
            primaryStage.hide();
        } else {
            primaryStage.show();
        }
    }

    /** 移除托盘图标（退出时调用，避免 AWT 线程阻止进程结束） */
    private void removeTray() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    /** 程序绘制托盘图标（32x32）：蓝色圆角底 + 太阳 + 云 */
    private static java.awt.Image createTrayImage() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        // 圆角蓝底
        g.setColor(new java.awt.Color(37, 99, 235));
        g.fillRoundRect(0, 0, size, size, 12, 12);
        // 太阳
        g.setColor(new java.awt.Color(253, 224, 71));
        g.fillOval(7, 5, 15, 15);
        // 云
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(4, 16, 15, 11);
        g.fillOval(13, 13, 16, 14);
        g.dispose();
        return img;
    }

    /** 首次启动（无默认定位）时：自动定位一次并保存为默认 */
    private void autoLocateOnStartup() {
        new Thread(() -> {
            try {
                LocationService.Location loc = LocationService.locateByIp();
                Platform.runLater(() -> applyLocation(loc.latitude(), loc.longitude(), loc.name()));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    locationLabel.setText("📍 定位失败，请手动设置");
                    new Thread(this::fetchWeatherData).start();
                });
            }
        }).start();
    }

    /** 右键菜单「自动定位」：按 IP 定位并应用 */
    private void autoLocateFromMenu() {
        if (locationLabel != null) {
            locationLabel.setText("📍 定位中…");
        }
        new Thread(() -> {
            try {
                LocationService.Location loc = LocationService.locateByIp();
                Platform.runLater(() -> applyLocation(loc.latitude(), loc.longitude(), loc.name()));
            } catch (Exception e) {
                Platform.runLater(() -> locationLabel.setText("📍 定位失败，请手动设置"));
            }
        }).start();
    }

    /** 重建「最近定位」子菜单：最多 5 条，点击即切换定位 */
    private void rebuildRecentMenu(Menu recentMenu) {
        recentMenu.getItems().clear();
        List<LocationService.Location> recents = getRecentLocations();
        if (recents.isEmpty()) {
            MenuItem none = new MenuItem("（暂无记录）");
            none.setDisable(true);
            recentMenu.getItems().add(none);
            return;
        }
        for (LocationService.Location loc : recents) {
            MenuItem item = new MenuItem(loc.name());
            item.setOnAction(e -> applyLocation(loc.latitude(), loc.longitude(), loc.name()));
            recentMenu.getItems().add(item);
        }
    }

    /**
     * 从 Open-Meteo API 获取逐小时天气数据。
     * forecast_days=2 保证临近午夜时也能取到"未来两小时"。
     */
    private void fetchWeatherData() {
        try {
            String url = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day," +
                            "apparent_temperature,relative_humidity_2m,wind_speed_10m" +
                            "&daily=temperature_2m_max,temperature_2m_min" +
                            "&forecast_days=2&timezone=auto",
                    latitude, longitude
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode hourly = root.get("hourly");
            JsonNode times = hourly.get("time");
            JsonNode temps = hourly.get("temperature_2m");
            JsonNode precipProbs = hourly.get("precipitation_probability");
            JsonNode precipAmounts = hourly.get("precipitation");
            JsonNode weatherCodes = hourly.get("weather_code");
            JsonNode isDays = hourly.get("is_day");
            JsonNode apparents = hourly.get("apparent_temperature");
            JsonNode humidities = hourly.get("relative_humidity_2m");
            JsonNode windSpeeds = hourly.get("wind_speed_10m");
            JsonNode daily = root.get("daily");
            JsonNode dailyMax = daily.get("temperature_2m_max");
            JsonNode dailyMin = daily.get("temperature_2m_min");

            // 按"定位城市当地时间"定位当前小时索引
            ZoneId zone = ZoneId.of(root.get("timezone").asText());
            List<LocalDateTime> timePoints = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                timePoints.add(LocalDateTime.parse(times.get(i).asText()));
            }
            int currentIndex = WeatherLogic.currentHourIndex(timePoints, LocalDateTime.now(zone));

            // 顶部展示：当前小时
            HourlyData current = new HourlyData(
                    LocalDateTime.parse(times.get(currentIndex).asText()).getHour(),
                    temps.get(currentIndex).asDouble(),
                    precipProbs.get(currentIndex).asInt(),
                    precipAmounts.get(currentIndex).asDouble(),
                    weatherCodes.get(currentIndex).asInt(),
                    isDays.get(currentIndex).asInt() == 1
            );

            // 当前小时详情：体感/湿度/风速取当前小时，高低温取今日 daily 首日
            CurrentDetail detail = new CurrentDetail(
                    apparents.get(currentIndex).asDouble(),
                    dailyMax.get(0).asDouble(),
                    dailyMin.get(0).asDouble(),
                    humidities.get(currentIndex).asInt(),
                    windSpeeds.get(currentIndex).asDouble()
            );

            // 轮播展示：未来三个小时（不含当前）
            int startIndex = currentIndex + 1;
            int endIndex = Math.min(startIndex + FORECAST_HOURS, times.size());

            List<HourlyData> forecasts = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                forecasts.add(new HourlyData(
                        LocalDateTime.parse(times.get(i).asText()).getHour(),
                        temps.get(i).asDouble(),
                        precipProbs.get(i).asInt(),
                        precipAmounts.get(i).asDouble(),
                        weatherCodes.get(i).asInt(),
                        isDays.get(i).asInt() == 1
                ));
            }

            if (forecasts.isEmpty()) {
                throw new IllegalStateException("未获取到预报数据");
            }

            // 未来 24 小时序列：用于启动简报与突变检测（含绝对时间，便于生成"明天 HH:00"文案）
            int alertEnd = Math.min(currentIndex + ALERT_WINDOW_HOURS, times.size());
            List<WeatherLogic.AlertPoint> alertWindow = new ArrayList<>();
            for (int i = currentIndex; i < alertEnd; i++) {
                alertWindow.add(new WeatherLogic.AlertPoint(
                        LocalDateTime.parse(times.get(i).asText()),
                        temps.get(i).asDouble(),
                        precipProbs.get(i).asInt(),
                        weatherCodes.get(i).asInt()
                ));
            }

            Platform.runLater(() -> {
                hasData = true;
                currentTempLabel.setText(String.format("%.0f°", current.temp()));
                currentEmojiLabel.setText(WeatherLogic.weatherEmoji(current.weatherCode(), current.isDay()));
                detailLabel.setText(detailText(detail));
                applyBackground(current.weatherCode());
                updateForecastUI(forecasts);
                maybeSendReminders(current, detail, alertWindow, zone);
            });

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                // 网络失败时沿用上次成功获取的数据，不清空界面
                if (hasData) {
                    return;
                }
                currentTempLabel.setText("--°");
                currentEmojiLabel.setText("🌡");
                detailLabel.setText("");
                stopCarousel();
                forecastContainer.getChildren().clear();
                Label errorLabel = new Label("获取天气失败");
                errorLabel.setTextFill(Color.web("#EF4444"));
                forecastContainer.getChildren().add(errorLabel);
            });
        }
    }

    /**
     * 获取未来 5 天预报（使用当前定位坐标），供设置窗口的"预报"分页调用。
     */
    public static List<WeatherLogic.DailyForecast> fetchDailyForecast() throws Exception {
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                        "precipitation_probability_max" +
                        "&hourly=temperature_2m,precipitation_probability" +
                        "&forecast_days=5&timezone=auto",
                latitude, longitude
        );

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response.body());
        return WeatherLogic.parseDaily(root, LocalDate.now(ZoneId.of(root.get("timezone").asText())));
    }

    // ===== 托盘提醒：启动今日简报 + 天气突变预警 =====

    /**
     * 推送提醒（系统托盘气泡）：
     * - 启动后首次成功获取数据时，推送一条"今日简报"；
     * - 检测到未来 24h 内天气突变时，推送按蓝/黄/橙/红四级描述的预警（按事件去重）。
     */
    private void maybeSendReminders(HourlyData current, CurrentDetail detail,
                                    List<WeatherLogic.AlertPoint> window, ZoneId zone) {
        if (trayIcon == null) {
            return;
        }

        if (!startupSummaryShown) {
            startupSummaryShown = true;
            String emoji = WeatherLogic.weatherEmoji(current.weatherCode(), current.isDay());
            int maxProb = window.stream()
                    .mapToInt(WeatherLogic.AlertPoint::precipProb).max().orElse(current.precipProb());
            String text = String.format("📍 %s · 今日 %s %.0f°~%.0f° · 降水概率 %d%%",
                    locationName, emoji, detail.tempMin(), detail.tempMax(), maxProb);
            trayIcon.displayMessage("今日天气", text, TrayIcon.MessageType.NONE);
        }

        WeatherLogic.WeatherAlert alert = WeatherLogic.detectChange(window, LocalDate.now(zone));
        if (alert != null && !alert.text().equals(lastAlertKey)) {
            lastAlertKey = alert.text();
            trayIcon.displayMessage("天气预警（" + alert.level() + "）", alert.text(),
                    TrayIcon.MessageType.WARNING);
        }
    }

    /** 更新预报 UI：渲染数据并启动轮播 */
    private void updateForecastUI(List<HourlyData> forecasts) {
        lastForecasts = forecasts;
        stopCarousel();
        renderRows(forecasts);
        startCarousel();
    }

    /** 渲染预报行（首行为下一小时） */
    private void renderRows(List<HourlyData> forecasts) {
        forecastContainer.getChildren().clear();
        forecastContainer.setTranslateY(0);
        for (HourlyData data : forecasts) {
            forecastContainer.getChildren().add(buildRow(data));
        }
    }

    /** 构建单行：时间、天气 emoji、温度、降雨概率、降水量详情 */
    private HBox buildRow(HourlyData data) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 8, 0, 8));
        row.setPrefHeight(ROW_HEIGHT);
        row.setMinHeight(ROW_HEIGHT);
        row.setMaxHeight(ROW_HEIGHT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle("-fx-background-radius: 8;");
        row.setOnMouseEntered(e ->
                row.setStyle("-fx-background-color: " + rowHoverColor + "; -fx-background-radius: 8;")
        );
        row.setOnMouseExited(e ->
                row.setStyle("-fx-background-radius: 8;")
        );

        // 时间
        Label timeLabel = new Label(String.format("%02d:00", data.hour()));
        timeLabel.setTextFill(secondaryText);
        timeLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        timeLabel.setMinWidth(44);

        // 天气 emoji（加大显示）
        Label emojiLabel = new Label(WeatherLogic.weatherEmoji(data.weatherCode(), data.isDay()));
        emojiLabel.setFont(Font.font("System", 24));
        emojiLabel.setMinWidth(34);

        // 温度
        Label tempLabel = new Label(String.format("%.0f°", data.temp()));
        tempLabel.setTextFill(primaryText);
        tempLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        tempLabel.setMinWidth(40);

        // 降雨概率
        Label probLabel = new Label(String.format("💧 %d%%", data.precipProb()));
        probLabel.setTextFill(accentText);
        probLabel.setFont(Font.font("System", 12));
        probLabel.setMinWidth(62);

        // 降雨量详情按钮（Tooltip 显示降水量）
        Button detailBtn = new Button("ℹ");
        detailBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-text-fill: " + toHex(secondaryText) + ";" +
                        "-fx-cursor: hand;" +
                        "-fx-font-size: 11;" +
                        "-fx-padding: 0 4 0 4;"
        );

        Tooltip tooltip = new Tooltip(
                String.format("该小时预报降水量: %.1f mm", data.precipAmount())
        );
        tooltip.setStyle(
                "-fx-background-color: #1F2937;" +
                        "-fx-text-fill: #F3F4F6;" +
                        "-fx-font-size: 12;" +
                        "-fx-background-radius: 6;" +
                        "-fx-padding: 6 10;"
        );
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(detailBtn, tooltip);

        row.getChildren().addAll(timeLabel, emojiLabel, tempLabel, probLabel, detailBtn);
        return row;
    }

    /**
     * 开始轮播：每步停留后上移一行，顶部行移到底部循环。
     * 累计超过 CAROUSEL_RUN_MILLIS 后自动停止并重置为显示下个小时，
     * 等待用户点击轮播区域重新开始。
     */
    private void startCarousel() {
        if (forecastContainer.getChildren().size() < 2) {
            return;
        }
        stopCarouselAnimations();
        carouselRunning = true;
        carouselStartNanos = System.nanoTime();
        scheduleNextStep();
    }

    private void scheduleNextStep() {
        if (!carouselRunning) {
            return;
        }
        if ((System.nanoTime() - carouselStartNanos) / 1_000_000L >= CAROUSEL_RUN_MILLIS) {
            stopCarouselAndReset();
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.seconds(CAROUSEL_PAUSE_SECONDS));
        pause.setOnFinished(e -> slideStep());
        carouselPause = pause;
        pause.play();
    }

    private void slideStep() {
        if (!carouselRunning) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(600), forecastContainer);
        slide.setFromY(0);
        slide.setToY(-ROW_STEP);
        slide.setInterpolator(Interpolator.EASE_BOTH);
        slide.setOnFinished(e -> {
            if (forecastContainer.getChildren().isEmpty()) {
                return;
            }
            Node firstRow = forecastContainer.getChildren().remove(0);
            forecastContainer.getChildren().add(firstRow);
            forecastContainer.setTranslateY(0);
            scheduleNextStep();
        });
        carouselSlide = slide;
        slide.play();
    }

    private void stopCarouselAnimations() {
        if (carouselPause != null) {
            carouselPause.stop();
            carouselPause = null;
        }
        if (carouselSlide != null) {
            carouselSlide.stop();
            carouselSlide = null;
        }
    }

    /** 停止轮播（用于数据刷新前的清理） */
    private void stopCarousel() {
        carouselRunning = false;
        stopCarouselAnimations();
    }

    /** 自动停止轮播并重置为显示"现在" */
    private void stopCarouselAndReset() {
        carouselRunning = false;
        stopCarouselAnimations();
        renderRows(lastForecasts);
    }

    /** Color -> #RRGGBB，用于内联样式 */
    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    // ===== 天气 -> 颜色 / emoji =====

    /** 天气对应的背景基色（RGB 字符串），用于动态背景 */
    private static String weatherRgb(int code) {
        if (code == 0) {
            return "37, 99, 235";            // 晴：蓝
        }
        if (code <= 2) {
            return "71, 105, 145";           // 少云
        }
        if (code == 3) {
            return "100, 116, 139";          // 阴：灰
        }
        if (code == 45 || code == 48) {
            return "120, 113, 108";          // 雾
        }
        if (code >= 51 && code <= 67) {
            return "30, 64, 124";            // 雨：深蓝
        }
        if (code >= 71 && code <= 77) {
            return "148, 163, 184";          // 雪：浅灰蓝
        }
        if (code >= 80 && code <= 82) {
            return "30, 64, 124";            // 阵雨
        }
        if (code >= 85 && code <= 86) {
            return "148, 163, 184";          // 阵雪
        }
        if (code >= 95) {
            return "76, 29, 149";            // 雷暴：紫
        }
        return "30, 34, 42";
    }

    /**
     * 按天气设置根背景：透明度 30% + 与天气相近的颜色；
     * 同时根据背景亮度切换文字深浅（浅底→深色字，深底→浅色字）。
     */
    private void applyBackground(int weatherCode) {
        String rgb = weatherRgb(weatherCode);
        rootBox.setStyle(
                "-fx-background-color: rgba(" + rgb + ", 0.3); " + ROOT_EFFECT
        );

        String[] parts = rgb.split(",");
        double r = Double.parseDouble(parts[0].trim()) / 255.0;
        double g = Double.parseDouble(parts[1].trim()) / 255.0;
        double b = Double.parseDouble(parts[2].trim()) / 255.0;
        double luminance = 0.299 * r + 0.587 * g + 0.114 * b;

        if (luminance > 0.55) {
            // 浅色背景 -> 深色文字
            primaryText = Color.web("#1F2937");
            secondaryText = Color.web("#4B5563");
            accentText = Color.web("#1D4ED8");
            rowHoverColor = "rgba(0,0,0,0.06)";
        } else {
            // 深色背景 -> 浅色文字
            primaryText = Color.WHITE;
            secondaryText = Color.web("#9fe4f7");
            accentText = Color.web("#60A5FA");
            rowHoverColor = "rgba(255,255,255,0.08)";
        }

        if (locationLabel != null) {
            locationLabel.setTextFill(secondaryText);
        }
        if (currentTempLabel != null) {
            currentTempLabel.setTextFill(primaryText);
        }
        if (detailLabel != null) {
            detailLabel.setTextFill(secondaryText);
        }
    }

    /** 当前天气详情文本：体感 / 高低温 / 湿度 / 风速 */
    private static String detailText(CurrentDetail d) {
        return String.format("体感 %.0f° · 高%.0f° 低%.0f° · 湿度 %d%% · 风 %.0fkm/h",
                d.apparent(), d.tempMax(), d.tempMin(), d.humidity(), d.windSpeed());
    }

    /** 内部数据类：单小时天气数据 */
    private record HourlyData(int hour, double temp, int precipProb, double precipAmount,
                              int weatherCode, boolean isDay) {}

    /** 内部数据类：当前天气详情 */
    private record CurrentDetail(double apparent, double tempMax, double tempMin,
                                 int humidity, double windSpeed) {}

    public static void main(String[] args) {
        launch(args);
    }
}
