package com.weatherwidget;

/**
 * IDE 启动入口。
 * 该类的 main 不继承 {@link javafx.application.Application}，因此可以从普通类路径
 * （而非 JavaFX 模块路径）启动。若直接以 {@link WeatherWidgetApp} 作为主类从类路径
 * 启动，JavaFX 会抛出 "JavaFX runtime components are missing"。
 * 系统托盘出现编码问题时，尝试mvn javafx:run启动项目
 */
public class Launcher {

    public static void main(String[] args) {
        WeatherWidgetApp.main(args);
    }
}
