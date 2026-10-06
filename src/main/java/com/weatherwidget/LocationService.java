package com.weatherwidget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 定位服务：
 * - locateByIp()  通过 IP 自动定位（ipwho.is 主源，ip-api.com 备源）
 * - searchCity()  按城市名搜索坐标（Open-Meteo Geocoding）
 */
public class LocationService {

    /** 定位结果 */
    public record Location(double latitude, double longitude, String name) {}

    private static HttpClient HTTP = HttpClient.newHttpClient();
    // 反向地理编码接口会返回 307 重定向，需显式跟随
    private static HttpClient HTTP_REDIRECT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocationService() {}

    /** 测试钩子：注入 mock 的 HttpClient（同时替换重定向客户端），仅测试使用 */
    static void setHttpClientForTest(HttpClient client) {
        HTTP = client;
        HTTP_REDIRECT = client;
    }

    /** 测试钩子：恢复默认的 HttpClient 实例，仅测试使用 */
    static void resetHttpClientForTest() {
        HTTP = HttpClient.newHttpClient();
        HTTP_REDIRECT = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 通过 IP 自动定位当前所在城市（多源容错） */
    public static Location locateByIp() throws Exception {
        try {
            return locateByIpWhoIs();
        } catch (Exception primary) {
            try {
                return locateByIpApiCom();
            } catch (Exception secondary) {
                throw new IllegalStateException("自动定位失败，请手动输入坐标");
            }
        }
    }

    /** 主源：ipwho.is */
    private static Location locateByIpWhoIs() throws Exception {
        JsonNode node = getJson("https://ipwho.is/");

        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(textOrDefault(node, "message", "定位失败"));
        }

        double lat = Double.parseDouble(textOrDefault(node, "latitude", ""));
        double lon = Double.parseDouble(textOrDefault(node, "longitude", ""));
        String city = textOrDefault(node, "city", "");
        String country = textOrDefault(node, "country", "");    
        return buildLocation(lat, lon, city, country);
    }

    /** 备源：ip-api.com（仅支持 HTTP） */
    private static Location locateByIpApiCom() throws Exception {
        JsonNode node = getJson("http://ip-api.com/json/");

        if (!"success".equals(textOrDefault(node, "status", ""))) {
            throw new IllegalStateException(textOrDefault(node, "message", "定位失败"));
        }

        double lat = Double.parseDouble(textOrDefault(node, "lat", "0"));
        double lon = Double.parseDouble(textOrDefault(node, "lon", "0"));
        String city = textOrDefault(node, "city", "");
        String country = textOrDefault(node, "country", "");
        return buildLocation(lat, lon, city, country);
    }

    private static Location buildLocation(double lat, double lon, String city, String country) {
        if (lat == 0 && lon == 0) {
            throw new IllegalStateException("定位返回无效坐标");
        }
        String name = city.isEmpty() ? country : (country.isEmpty() ? city : city + ", " + country);
        return new Location(lat, lon, name.isEmpty() ? "未知位置" : name);
    }

    /** 读取文本字段；字段缺失或为显式 null 时返回默认值（替代已弃用的 asText(String)） */
    private static String textOrDefault(JsonNode parent, String field, String defaultValue) {
        JsonNode node = parent.path(field);
        return (node.isMissingNode() || node.isNull()) ? defaultValue : node.asText();
    }

    /** 发送 GET 请求并解析 JSON，非 JSON 响应（如挑战页）会抛异常 */
    private static JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "WeatherWidget/1.0")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = MAPPER.readTree(response.body());
        if (node == null || node.isMissingNode()) {
            throw new IllegalStateException("定位服务返回内容异常");
        }
        return node;
    }

    /**
     * 反向地理编码：根据坐标解析当前所在的行政区名称（简体中文）。
     * 使用 BigDataCloud（会 307 重定向，需跟随），失败或无有效结果时返回 null。
     * 返回形如「XX市 · XX省」。
     */
    public static String reverseGeocodeAdmin(double lat, double lon) {
        try {
            String url = String.format(
                    "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                            "?latitude=%.4f&longitude=%.4f&localityLanguage=zh-Hans",
                    lat, lon);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "WeatherWidget/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_REDIRECT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = MAPPER.readTree(response.body());
            if (node == null || node.isMissingNode()) {
                return null;
            }

            String city = node.path("city").asText();
            String locality = node.path("locality").asText();
            String subdivision = node.path("principalSubdivision").asText();
            String country = node.path("countryName").asText();

            // 主行政区：优先城市，其次区县，再次省级，最后国家
            String primary = !city.isEmpty() ? city
                    : (!locality.isEmpty() ? locality
                    : (!subdivision.isEmpty() ? subdivision : country));
            if (primary.isEmpty()) {
                return null;
            }

            // 上级行政区：优先省/州，其次国家（与主行政区去重）
            String secondary = "";
            if (!subdivision.isEmpty() && !subdivision.equals(primary)) {
                secondary = subdivision;
            } else if (!country.isEmpty() && !country.equals(primary)) {
                secondary = country;
            }
            return secondary.isEmpty() ? primary : primary + " · " + secondary;
        } catch (Exception e) {
            // 反向地理编码失败不影响主流程，交由调用方回退为坐标显示
            return null;
        }
    }

    /** 按城市名搜索候选坐标列表（含行政区划，可选是否附带坐标以区分同名地点） */
    public static List<Location> searchCity(String query, boolean showCoordinates) throws Exception {
        List<JsonNode> raw = new ArrayList<>(queryGeocoding(query));

        // GeoNames 对中文名按精确匹配：因此追加"市"再查一次；最终按人口降序，让主要城市排在前面
        String trimmed = query.trim();
        if (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last != '市' && last != '县' && last != '区' && last != '州') {
                raw.addAll(queryGeocoding(trimmed + "市"));
            }
        }

        raw.sort((a, b) -> Integer.compare(textOrDefault(b, "population", "0").isEmpty() ? 0 : Integer.parseInt(textOrDefault(b, "population", "0")),
                textOrDefault(a, "population", "0").isEmpty() ? 0 : Integer.parseInt(textOrDefault(a, "population", "0"))));

        List<Location> locations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode r : raw) {
            double lat = Double.parseDouble(textOrDefault(r, "latitude", ""));
            double lon = Double.parseDouble(textOrDefault(r, "longitude", ""));
            String name = textOrDefault(r, "name", "");
            String admin2 = textOrDefault(r, "admin2", "");
            String admin1 = textOrDefault(r, "admin1", "");
            String country = textOrDefault(r, "country", "");

            // 行政区划逐级拼接（去重），用于区分同名地点
            List<String> regionParts = new ArrayList<>();
            if (!admin2.isEmpty()) {
                regionParts.add(admin2);
            }
            if (!admin1.isEmpty() && !admin1.equals(admin2)) {
                regionParts.add(admin1);
            }
            if (!country.isEmpty()) {
                regionParts.add(country);
            }

            StringBuilder label = new StringBuilder(name);
            if (!regionParts.isEmpty()) {
                label.append(" (").append(String.join(" · ", regionParts)).append(")");
            }
            // 可选附带坐标，避免行政区划完全相同的同名地点无法区分
            if (showCoordinates) {
                label.append(String.format("  [%.2f, %.2f]", lat, lon));
            }

            // 以最终标签去重（两次查询可能返回同一地点）
            if (seen.add(label.toString())) {
                locations.add(new Location(lat, lon, label.toString()));
            }
        }
        return locations;
    }

    /** 调用一次 Geocoding 接口，返回 results 数组 */
    private static List<JsonNode> queryGeocoding(String name) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&count=10&language=zh&format=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode results = MAPPER.readTree(response.body()).path("results");

        List<JsonNode> list = new ArrayList<>();
        if (results.isArray()) {
            results.forEach(list::add);
        }
        return list;
    }
}
