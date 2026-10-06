package servlet;

import lombok.extern.slf4j.Slf4j;
import util.AuthConfig;
import util.QWeatherJwtUtil;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@WebServlet("/api/other/weather")
public class WeatherServlet extends HttpServlet {

    // 专属API Host ★★★
    private static final String API_HOST = AuthConfig.get("api.host", "https://j4436k8n22.re.qweatherapi.com");
    private static final Pattern CITY_ID_PATTERN = Pattern.compile("^[0-9]{7,12}$");
    private static final Pattern COORDINATE_PATTERN = Pattern.compile("^(-?[0-9]{1,3}(?:\\.[0-9]{1,6})?),(-?[0-9]{1,2}(?:\\.[0-9]{1,6})?)$");
    private static final Pattern CITY_NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}·\\-\\s]{1,40}$");
    private static final int MAX_RESPONSE_CHARS = 256 * 1024;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
        resp.setContentType("application/json;charset=UTF-8");

        // ★禁止浏览器缓存天气数据
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        String location = req.getParameter("location");   // 可能是 "116.41,39.92" 或 "101010100"
        String cityName = req.getParameter("cityName");   // 定位失败时的默认城市名

        try {
            if (!isValidLocation(location) || !isValidCityName(cityName)) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().write("{\"code\":400,\"msg\":\"城市参数格式错误\",\"data\":null}");
                return;
            }
            String token = QWeatherJwtUtil.generateToken();
            String apiUrl;
            String displayCity;

            if (location != null && !location.isBlank()) {
                // 前端传了经纬度（如 "116.41,39.92"）或城市ID
                apiUrl = API_HOST + "/v7/weather/now?location=" + encode(location);
                displayCity = null; // 后面通过GeoAPI反查城市名
            } else if (cityName != null && !cityName.isBlank()) {
                // ★ 情况2：只有城市名，先通过GeoAPI查城市ID
                String cityId = searchCityId(cityName, token);
                if (cityId == null) {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    resp.getWriter().write("{\"code\":404,\"msg\":\"未找到对应城市\",\"data\":null}");
                    return;
                }
                apiUrl = API_HOST + "/v7/weather/now?location=" + cityId;
                displayCity = cityName;
            } else {
                // ★ 情况3：什么都没传，默认韶关
                apiUrl = API_HOST + "/v7/weather/now?location=101280201";
                displayCity = "韶关";
            }

            // 调用天气API
            String qweatherResp = callApi(apiUrl, token);
//            System.out.println("[Weather] 请求URL: " + apiUrl);
//            System.out.println("[Weather] 和风返回: " + qweatherResp);

            // 检查返回
            if (qweatherResp.startsWith("{\"error\"")) {
                resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                resp.getWriter().write("{\"code\":502,\"msg\":\"天气服务暂时不可用\",\"data\":null}");
                return;
            }

            String qwCode = extractValue(qweatherResp, "\"code\"");
            if (!"200".equals(qwCode)) {
                log.warn("天气服务返回异常状态：{}", qwCode);
                resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                resp.getWriter().write("{\"code\":502,\"msg\":\"天气服务暂时不可用\",\"data\":null}");
                return;
            }

            // 解析天气数据
            String updateTime = extractValue(qweatherResp, "\"updateTime\"");
            String nowBlock = extractJsonObject(qweatherResp, "\"now\"");
            String temp = extractValue(nowBlock, "\"temp\"");
            String weatherText = extractValue(nowBlock, "\"text\"");
            String weatherCode = extractValue(nowBlock, "\"icon\"");

            // ★ 如果前端传的是经纬度，通过GeoAPI反查城市名
            if (displayCity == null && location.contains(",")) {
                displayCity = reverseCity(location, token);
            }
            if (displayCity == null || displayCity.isBlank()) {
                displayCity = "当前位置";
            }

            // 组装返回
            String json = "{\"code\":200,\"msg\":\"success\"," +
                    "\"data\":{" +
                    "\"temp\":\"" + escape(temp) + "\"," +
                    "\"weatherCode\":\"" + escape(weatherCode) + "\"," +
                    "\"weatherText\":\"" + escape(weatherText) + "\"," +
                    "\"city\":\"" + escape(displayCity) + "\"," +
                    "\"updateTime\":\"" + escape(updateTime) + "\"" +
                    "}}";
            resp.getWriter().write(json);

        } catch (Exception e) {
            log.error("天气查询失败", e);
            try {
                resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                resp.getWriter().write("{\"code\":502,\"msg\":\"天气服务暂时不可用\",\"data\":null}");
            } catch (Exception ignored) {}
        }
    }

    /**
     * 调用和风天气API（自动处理gzip）
     */
    private String callApi(String apiUrl, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Authorization", "Bearer " + token);

        int status = conn.getResponseCode();
        InputStream rawStream = (status >= 200 && status < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        if (rawStream == null) rawStream = conn.getInputStream();

        // 检测gzip魔数并解压
        BufferedInputStream bis = new BufferedInputStream(rawStream);
        bis.mark(2);
        int b1 = bis.read(), b2 = bis.read();
        bis.reset();
        InputStream finalStream = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(bis) : bis;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() + line.length() > MAX_RESPONSE_CHARS) {
                    throw new IOException("天气服务响应超过大小限制");
                }
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 通过城市名查城市ID（GeoAPI 城市搜索）
     * 例如：北京 → 101010100
     */
    private String searchCityId(String cityName, String token) throws Exception {
        String url = API_HOST + "/geo/v2/city/lookup?location="
                + URLEncoder.encode(cityName, StandardCharsets.UTF_8) + "&number=1";
        String resp = callApi(url, token);
//        System.out.println("[Weather] GeoAPI返回: " + resp);
        String firstCity = extractJsonObject(resp, "\"location\"");
        // location是数组，取第一个对象
        if (firstCity.startsWith("{")) {
            return extractValue(firstCity, "\"id\"");
        }
        // 如果是数组格式 [{"id":"101010100",...}]
        int idx = resp.indexOf("\"id\"");
        if (idx < 0) return null;
        return extractValue(resp, "\"id\"");
    }

    /**
     * 通过经纬度反查城市名（用 city/lookup 接口）
     * 例如：113.75,23.02 → 东莞
     */
    private String reverseCity(String lngLat, String token) {
        try {
            // ★ 正确接口是 /geo/v2/city/lookup，支持经纬度查询
            String url = API_HOST + "/geo/v2/city/lookup?location=" + encode(lngLat) + "&number=1";
            String resp = callApi(url, token);
//            System.out.println("[Weather] 反查城市返回: " + resp);

            // 返回格式: {"code":"200","location":[{"id":"101281601","name":"东莞","adm1":"广东省","adm2":"东莞市",...}]}
            // 提取第一个城市对象
            int arrStart = resp.indexOf('[');
            int arrEnd = resp.indexOf(']');
            if (arrStart < 0 || arrEnd < 0) return null;

            String arrContent = resp.substring(arrStart, arrEnd + 1);
            // 取第一个 {...}
            int objStart = arrContent.indexOf('{');
            int objEnd = arrContent.indexOf('}');
            if (objStart < 0 || objEnd < 0) return null;

            String firstCity = arrContent.substring(objStart, objEnd + 1);
//            System.out.println("[Weather] 第一个城市: " + firstCity);

            // 优先取 adm2（地级市），没有就取 name
            String city = extractValue(firstCity, "\"adm2\"");
            if (city.isBlank()) {
                city = extractValue(firstCity, "\"name\"");
            }
            if (city.isBlank()) {
                city = extractValue(firstCity, "\"adm1\"");
            }
            return city;
        } catch (Exception e) {
//            System.out.println("[Weather] 反查城市失败: " + e.getMessage());
            return null;
        }
    }

    // ========== JSON解析工具方法（和之前一样）==========

    private String extractValue(String json, String key) {
        if (json == null) return "";
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return "";
        int i = colonIdx + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) return "";
            return json.substring(i + 1, end);
        } else {
            int end = i;
            while (end < json.length() && json.charAt(end) != ','
                    && json.charAt(end) != '}' && json.charAt(end) != ' ') end++;
            return json.substring(i, end);
        }
    }

    private String extractJsonObject(String json, String key) {
        if (json == null) return "{}";
        int idx = json.indexOf(key);
        if (idx < 0) return "{}";
        int start = json.indexOf('{', idx);
        if (start < 0) return "{}";
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return "{}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private boolean isValidLocation(String location) {
        if (location == null || location.isBlank()) {
            return true;
        }
        String value = location.trim();
        if (CITY_ID_PATTERN.matcher(value).matches()) {
            return true;
        }
        Matcher matcher = COORDINATE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        try {
            double longitude = Double.parseDouble(matcher.group(1));
            double latitude = Double.parseDouble(matcher.group(2));
            return longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
        } catch (NumberFormatException err) {
            return false;
        }
    }

    private boolean isValidCityName(String cityName) {
        return cityName == null || cityName.isBlank() || CITY_NAME_PATTERN.matcher(cityName.trim()).matches();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
