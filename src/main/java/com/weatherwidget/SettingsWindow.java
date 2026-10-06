package com.weatherwidget;

import com.weatherwidget.LocationService.Location;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;

public class SettingsWindow {

    private static final String FIELD_STYLE =
            "-fx-background-color: #374151;" +
                    "-fx-text-fill: white;" +
                    "-fx-background-radius: 8;" +
                    "-fx-padding: 8 12;";

    public static void open(Stage ownerStage) {
        // 重置上次窗口遗留的展开状态
        expandedDetail = null;
        expandedArrow = null;

        Stage settingsStage = new Stage();
        settingsStage.initOwner(ownerStage);
        settingsStage.initModality(Modality.APPLICATION_MODAL);
        settingsStage.initStyle(StageStyle.TRANSPARENT);

        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.CENTER);
        root.setStyle(
                "-fx-background-color: #1F2937;" +
                        "-fx-background-radius: 16;"
        );

        Label title = new Label("⚙ 天气设置");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("System", FontWeight.BOLD, 18));

        // 关闭按钮
        Button closeBtn = new Button("✕");
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-text-fill: #9CA3AF;" +
                        "-fx-cursor: hand;" +
                        "-fx-font-size: 14;" +
                        "-fx-padding: 0 6 0 6;"
        );
        closeBtn.setOnAction(e -> settingsStage.close());

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        header.getChildren().addAll(title, closeBtn);

        // 允许拖动无边框的设置窗口（按住标题栏拖动）
        final double[] dragOffset = new double[2];
        header.setStyle("-fx-cursor: move;");
        header.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        header.setOnMouseDragged(e -> {
            settingsStage.setX(e.getScreenX() - dragOffset[0]);
            settingsStage.setY(e.getScreenY() - dragOffset[1]);
        });

        // ===== 自动定位按钮 =====
        Button autoBtn = new Button("📍 自动定位");
        autoBtn.setMaxWidth(Double.MAX_VALUE);
        autoBtn.setStyle(
                "-fx-background-color: #10B981;" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 10 24;" +
                        "-fx-cursor: hand;" +
                        "-fx-font-weight: bold;"
        );

        // 状态提示（定位/搜索结果或错误）
        Label statusLabel = new Label("");
        statusLabel.setTextFill(Color.web("#9CA3AF"));
        statusLabel.setFont(Font.font("System", 11));
        statusLabel.setWrapText(true);

        Label manualHint = new Label("或手动定位");
        manualHint.setTextFill(Color.web("#6B7280"));
        manualHint.setFont(Font.font("System", 11));

        // ===== 城市名搜索 =====
        TextField cityField = new TextField();
        cityField.setPromptText("输入城市名，如 北京 / beijing");
        cityField.setStyle(FIELD_STYLE);
        HBox.setHgrow(cityField, Priority.ALWAYS);

        Button searchBtn = new Button("搜索");
        searchBtn.setStyle(
                "-fx-background-color: #4B5563;" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 8 14;" +
                        "-fx-cursor: hand;"
        );

        HBox searchRow = new HBox(8);
        searchRow.setAlignment(Pos.CENTER);
        searchRow.getChildren().addAll(cityField, searchBtn);

        ComboBox<Location> resultBox = new ComboBox<>();
        resultBox.setMaxWidth(Double.MAX_VALUE);
        resultBox.setPromptText("选择搜索结果");
        resultBox.setVisible(false);
        resultBox.setManaged(false);
        // 悬停显示完整地名，避免同名地点被截断
        Tooltip resultTip = new Tooltip();
        resultBox.setTooltip(resultTip);
        resultBox.valueProperty().addListener((obs, old, val) ->
                resultTip.setText(val == null ? "" : val.name())
        );

        // ===== 是否在城市名称后显示坐标 =====
        CheckBox showCoordsBox = new CheckBox("在城市名称后显示坐标");
        showCoordsBox.setSelected(WeatherWidgetApp.isShowCoordinates());
        showCoordsBox.setTextFill(Color.web("#D1D5DB"));
        showCoordsBox.setFont(Font.font("System", 12));
        showCoordsBox.setStyle("-fx-cursor: hand;");

        // ===== 坐标定位是否自动转换为行政区 =====
        CheckBox autoRegionBox = new CheckBox("坐标定位自动转换为行政区");
        autoRegionBox.setSelected(WeatherWidgetApp.isAutoAdminRegion());
        autoRegionBox.setTextFill(Color.web("#D1D5DB"));
        autoRegionBox.setFont(Font.font("System", 12));
        autoRegionBox.setStyle("-fx-cursor: hand;");

        Label autoRegionHint = new Label("关闭后，坐标为来源的定位将直接显示坐标");
        autoRegionHint.setTextFill(Color.web("#8A94A6"));
        autoRegionHint.setFont(Font.font("System", 10));
        autoRegionHint.setWrapText(true);

        // ===== 经纬度输入 =====
        Label latLabel = new Label("纬度 (Latitude)");
        latLabel.setTextFill(Color.web("#9CA3AF"));
        latLabel.setFont(Font.font("System", 12));

        TextField latField = new TextField(String.valueOf(WeatherWidgetApp.getLatitude()));
        latField.setStyle(FIELD_STYLE);

        Label lonLabel = new Label("经度 (Longitude)");
        lonLabel.setTextFill(Color.web("#9CA3AF"));
        lonLabel.setFont(Font.font("System", 12));

        TextField lonField = new TextField(String.valueOf(WeatherWidgetApp.getLongitude()));
        lonField.setStyle(FIELD_STYLE);

        // ===== 开机自启动 =====
        CheckBox autoStartBox = new CheckBox("开机自动启动");
        autoStartBox.setTextFill(Color.web("#D1D5DB"));
        autoStartBox.setFont(Font.font("System", 12));
        autoStartBox.setStyle("-fx-cursor: hand;");
        boolean autoStartSupported = AutoStart.isSupported();
        autoStartBox.setDisable(!autoStartSupported);
        autoStartBox.setSelected(autoStartSupported && AutoStart.isEnabled());

        Label autoStartHint = new Label(autoStartSupported
                ? "随 Windows 登录自动启动天气组件"
                : "仅打包为 exe 后可用");
        autoStartHint.setTextFill(Color.web("#8A94A6"));
        autoStartHint.setFont(Font.font("System", 10));
        autoStartHint.setWrapText(true);

        // ===== 分页：定位 / 通用 =====
        VBox locationPane = new VBox(10,
                autoBtn, statusLabel, manualHint, searchRow, resultBox,
                latLabel, latField, lonLabel, lonField);
        locationPane.setPadding(new Insets(12, 4, 4, 4));

        VBox generalPane = new VBox(10, showCoordsBox, autoRegionBox, autoRegionHint,
                autoStartBox, autoStartHint);
        generalPane.setPadding(new Insets(12, 4, 4, 4));

        // ===== 分页：未来几天预报 =====
        VBox forecastPane = new VBox(8);
        forecastPane.setPadding(new Insets(12, 4, 4, 4));
        Label forecastStatus = new Label("加载中…");
        forecastStatus.setTextFill(Color.web("#9CA3AF"));
        forecastStatus.setFont(Font.font("System", 12));
        forecastPane.getChildren().add(forecastStatus);

        // 展开某天图表后内容会变高，用 ScrollPane 保证可滚动查看
        ScrollPane forecastScroll = new ScrollPane(forecastPane);
        forecastScroll.setFitToWidth(true);
        forecastScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        forecastScroll.getStyleClass().add("settings-scroll");

        Tab locationTab = new Tab("📍 定位", locationPane);
        locationTab.setClosable(false);
        Tab forecastTab = new Tab("📅 预报", forecastScroll);
        forecastTab.setClosable(false);
        Tab generalTab = new Tab("⚙ 通用", generalPane);
        generalTab.setClosable(false);

        TabPane tabPane = new TabPane(locationTab, forecastTab, generalTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("settings-tab-pane");
        tabPane.setStyle("-fx-background-color: transparent;");
        tabPane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        // ===== 保存按钮 =====
        Button saveBtn = new Button("保存并刷新");
        saveBtn.setStyle(
                "-fx-background-color: #3B82F6;" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 10 24;" +
                        "-fx-cursor: hand;" +
                        "-fx-font-weight: bold;"
        );

        // ===== 事件：自动定位 =====
        autoBtn.setOnAction(e -> {
            autoBtn.setDisable(true);
            statusLabel.setTextFill(Color.web("#9CA3AF"));
            statusLabel.setText("定位中...");
            new Thread(() -> {
                try {
                    Location loc = LocationService.locateByIp();
                    Platform.runLater(() -> {
                        latField.setText(String.format("%.4f", loc.latitude()));
                        lonField.setText(String.format("%.4f", loc.longitude()));
                        cityField.setText(loc.name());
                        resultBox.setVisible(false);
                        resultBox.setManaged(false);
                        statusLabel.setTextFill(Color.web("#34D399"));
                        statusLabel.setText("已定位: " + loc.name());
                        autoBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        statusLabel.setTextFill(Color.web("#EF4444"));
                        statusLabel.setText("定位失败: " + ex.getMessage());
                        autoBtn.setDisable(false);
                    });
                }
            }).start();
        });

        // ===== 事件：城市名搜索 =====
        Runnable searchAction = () -> {
            String query = cityField.getText().trim();
            if (query.isEmpty()) {
                return;
            }
            searchBtn.setDisable(true);
            statusLabel.setTextFill(Color.web("#9CA3AF"));
            statusLabel.setText("搜索中...");
            boolean showCoords = showCoordsBox.isSelected();
            new Thread(() -> {
                try {
                    List<Location> results = LocationService.searchCity(query, showCoords);
                    Platform.runLater(() -> {
                        resultBox.getItems().setAll(results);
                        boolean visible = !results.isEmpty();
                        resultBox.setVisible(visible);
                        resultBox.setManaged(visible);
                        if (visible) {
                            resultBox.getSelectionModel().selectFirst();
                            statusLabel.setText("");
                        } else {
                            statusLabel.setTextFill(Color.web("#EF4444"));
                            statusLabel.setText("未找到匹配的城市");
                        }
                        searchBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        statusLabel.setTextFill(Color.web("#EF4444"));
                        statusLabel.setText("搜索失败: " + ex.getMessage());
                        searchBtn.setDisable(false);
                    });
                }
            }).start();
        };
        searchBtn.setOnAction(e -> searchAction.run());

        // 切换"显示坐标"后，若已有搜索结果则重新查询以更新显示
        showCoordsBox.selectedProperty().addListener((obs, old, val) -> {
            if (!cityField.getText().trim().isEmpty()) {
                searchAction.run();
            }
        });

        // ===== 事件：选择搜索结果回填经纬度 =====
        resultBox.setOnAction(e -> {
            Location loc = resultBox.getValue();
            if (loc != null) {
                latField.setText(String.format("%.4f", loc.latitude()));
                lonField.setText(String.format("%.4f", loc.longitude()));
            }
        });

        // ===== 事件：保存 =====
        saveBtn.setOnAction(e -> {
            double lat;
            double lon;
            try {
                lat = Double.parseDouble(latField.getText().trim());
                lon = Double.parseDouble(lonField.getText().trim());
            } catch (NumberFormatException ex) {
                tabPane.getSelectionModel().select(locationTab);
                statusLabel.setTextFill(Color.web("#EF4444"));
                statusLabel.setText("经纬度必须是数字");
                return;
            }
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                tabPane.getSelectionModel().select(locationTab);
                statusLabel.setTextFill(Color.web("#EF4444"));
                statusLabel.setText("坐标超出有效范围（纬度 -90~90，经度 -180~180）");
                return;
            }

            // 开机自启动：写入/移除注册表 Run 项
            if (AutoStart.isSupported()) {
                try {
                    AutoStart.setEnabled(autoStartBox.isSelected());
                } catch (Exception ex) {
                    tabPane.getSelectionModel().select(generalTab);
                    autoStartHint.setTextFill(Color.web("#EF4444"));
                    autoStartHint.setText("设置开机自启动失败: " + ex.getMessage());
                    return;
                }
            }

            String name;
            Location selected = resultBox.getValue();
            if (selected != null && resultBox.isVisible()) {
                name = selected.name();
            } else if (!cityField.getText().trim().isEmpty()) {
                name = cityField.getText().trim();
            } else {
                name = String.format("%.2f, %.2f", lat, lon);
            }

            WeatherWidgetApp.setShowCoordinates(showCoordsBox.isSelected());
            WeatherWidgetApp.setAutoAdminRegion(autoRegionBox.isSelected());
            // 应用定位：更新内存/默认定位、记录最近定位、刷新标签与天气数据
            WeatherWidgetApp.applyLocation(lat, lon, name);
            settingsStage.close();
        });

        saveBtn.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().addAll(header, tabPane, saveBtn);

        Scene scene = new Scene(root, 400, 560);
        scene.setFill(Color.TRANSPARENT);
        java.net.URL css = SettingsWindow.class.getResource("/settings.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        settingsStage.setScene(scene);
        settingsStage.show();

        // ===== 异步加载未来 5 天预报 =====
        new Thread(() -> {
            try {
                List<WeatherLogic.DailyForecast> days = WeatherWidgetApp.fetchDailyForecast();
                Platform.runLater(() -> {
                    forecastPane.getChildren().clear();
                    for (WeatherLogic.DailyForecast d : days) {
                        forecastPane.getChildren().add(buildDayRow(d));
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    forecastStatus.setTextFill(Color.web("#EF4444"));
                    forecastStatus.setText("加载失败: " + ex.getMessage());
                });
            }
        }).start();
    }

    // 手风琴：当前展开的日详情面板与指示箭头，展开一天时收起其它
    private static VBox expandedDetail;
    private static Label expandedArrow;

    /** 构建单日预报行：日期 / 天气 emoji / 高低温 / 降水概率；点击可展开当日图表 */
    private static VBox buildDayRow(WeatherLogic.DailyForecast d) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));
        row.setStyle("-fx-background-color: #374151; -fx-background-radius: 8; -fx-cursor: hand;");

        Label dayLabel = new Label(d.label());
        dayLabel.setTextFill(Color.web("#D1D5DB"));
        dayLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        dayLabel.setMinWidth(76);

        Label emojiLabel = new Label(d.emoji());
        emojiLabel.setTextFill(Color.WHITE);
        emojiLabel.setFont(Font.font("System", 18));
        emojiLabel.setMinWidth(30);

        Label tempLabel = new Label(String.format("%.0f° / %.0f°", d.tempMax(), d.tempMin()));
        tempLabel.setTextFill(Color.WHITE);
        tempLabel.setFont(Font.font("System", 12));
        tempLabel.setMinWidth(84);

        Label probLabel = new Label(String.format("💧 %d%%", d.precipProb()));
        probLabel.setTextFill(Color.web("#60A5FA"));
        probLabel.setFont(Font.font("System", 11));

        // 右侧展开指示箭头
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label arrow = new Label("▸");
        arrow.setTextFill(Color.web("#9CA3AF"));
        arrow.setFont(Font.font("System", 11));

        row.getChildren().addAll(dayLabel, emojiLabel, tempLabel, probLabel, spacer, arrow);

        VBox detail = buildDayDetail(d);
        detail.setVisible(false);
        detail.setManaged(false);

        row.setOnMouseEntered(e -> row.setStyle(
                "-fx-background-color: #435063; -fx-background-radius: 8; -fx-cursor: hand;"));
        row.setOnMouseExited(e -> row.setStyle(
                "-fx-background-color: #374151; -fx-background-radius: 8; -fx-cursor: hand;"));
        row.setOnMouseClicked(e -> toggleDay(detail, arrow));

        return new VBox(6, row, detail);
    }

    /** 展开/收起某天详情（手风琴） */
    private static void toggleDay(VBox detail, Label arrow) {
        boolean show = !detail.isVisible();
        // 先收起之前展开的一天
        if (expandedDetail != null && expandedDetail != detail) {
            expandedDetail.setVisible(false);
            expandedDetail.setManaged(false);
            if (expandedArrow != null) {
                expandedArrow.setText("▸");
            }
        }
        detail.setVisible(show);
        detail.setManaged(show);
        arrow.setText(show ? "▾" : "▸");
        expandedDetail = show ? detail : null;
        expandedArrow = show ? arrow : null;
    }

    /** 构建当日详情面板：上为气温折线图，下为降水概率柱状图 */
    private static VBox buildDayDetail(WeatherLogic.DailyForecast d) {
        double width = 300;
        Canvas tempCanvas = new Canvas(width, 72);
        Canvas rainCanvas = new Canvas(width, 56);
        drawTempChart(tempCanvas, d.hourly());
        drawRainChart(rainCanvas, d.hourly());

        VBox detail = new VBox(4,
                chartLabel("气温变化 (℃)"), tempCanvas,
                chartLabel("降水概率 (%)"), rainCanvas);
        detail.setPadding(new Insets(8, 10, 8, 10));
        detail.setStyle("-fx-background-color: #1F2937; -fx-background-radius: 8;");
        return detail;
    }

    private static Label chartLabel(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.web("#9CA3AF"));
        label.setFont(Font.font("System", 10));
        return label;
    }

    /** 绘制气温折线（红色），并在左侧标注当日最高/最低温度 */
    private static void drawTempChart(Canvas canvas, List<WeatherLogic.HourlyPoint> points) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (points == null || points.size() < 2) {
            return;
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (WeatherLogic.HourlyPoint p : points) {
            min = Math.min(min, p.temp());
            max = Math.max(max, p.temp());
        }
        if (max - min < 1) {
            max = min + 1; // 避免除零
        }

        double padTop = 10;
        double padBottom = 12;
        double usable = h - padTop - padBottom;
        int n = points.size();

        g.setStroke(Color.web("#F87171"));
        g.setLineWidth(2);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double x = i * (w - 1) / (n - 1);
            double y = padTop + (1 - (points.get(i).temp() - min) / (max - min)) * usable;
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();

        // 最高/最低温度标注
        g.setFill(Color.web("#9CA3AF"));
        g.setFont(Font.font("System", 9));
        g.fillText(String.format("%.0f°", max), 2, 9);
        g.fillText(String.format("%.0f°", min), 2, h - 2);
    }

    /** 绘制降水概率柱状（蓝色），底部每 6 小时一个刻度 */
    private static void drawRainChart(Canvas canvas, List<WeatherLogic.HourlyPoint> points) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (points == null || points.isEmpty()) {
            return;
        }

        double padTop = 6;
        double padBottom = 12;
        double usable = h - padTop - padBottom;
        int n = points.size();
        double slot = w / n;
        double barWidth = Math.max(1, slot - 1);

        g.setFill(Color.web("#60A5FA"));
        for (int i = 0; i < n; i++) {
            double value = Math.max(0, Math.min(100, points.get(i).precipProb()));
            double barHeight = usable * value / 100.0;
            g.fillRect(i * slot, padTop + (usable - barHeight), barWidth, barHeight);
        }

        g.setFill(Color.web("#9CA3AF"));
        g.setFont(Font.font("System", 9));
        for (int i = 0; i < n; i += 6) {
            g.fillText(String.format("%d时", points.get(i).hour()), i * slot, h - 2);
        }
    }
}
