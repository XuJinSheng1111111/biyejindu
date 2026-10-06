package service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import util.SecurityUtil;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 毕业学分自查的匿名运营统计与问题反馈存储。
 * 仅保存随机访客标识的摘要，不保存 IP、浏览器指纹或成绩文件。
 */
@Slf4j
public final class CreditAuditOperationsService {

    private static final String VISITOR_COOKIE = "CF_VISITOR";
    private static final int MAX_FEEDBACK = 2_000;
    private static final int MAX_VISITOR_HASHES = 100_000;
    private static final int MAX_DAILY_VISITOR_HASHES = 20_000;
    private static final int RETENTION_DAYS = 90;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CreditAuditOperationsService INSTANCE = new CreditAuditOperationsService();

    private final Path dataFile;
    private State state;

    private CreditAuditOperationsService() {
        dataFile = resolveDataRoot().resolve("operations.json");
        state = load();
        prune();
    }

    public static CreditAuditOperationsService getInstance() {
        return INSTANCE;
    }

    public synchronized String identify(HttpServletRequest request, HttpServletResponse response) {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (VISITOR_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null
                        && cookie.getValue().matches("[A-Za-z0-9_-]{40,60}")) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token == null) {
            token = SecurityUtil.randomToken();
            String path = request.getContextPath();
            if (path == null || path.isBlank()) path = "/";
            StringBuilder value = new StringBuilder(VISITOR_COOKIE).append('=').append(token)
                    .append("; Path=").append(path)
                    .append("; Max-Age=31536000; HttpOnly; SameSite=Strict");
            if (request.isSecure()) value.append("; Secure");
            response.addHeader("Set-Cookie", value.toString());
        }
        return SecurityUtil.sha256(token);
    }

    public synchronized void recordVisit(String visitorHash) {
        DailyStat daily = today();
        boolean changed = addBounded(state.visitorHashes, visitorHash, MAX_VISITOR_HASHES)
                | addBounded(daily.visitorHashes, visitorHash, MAX_DAILY_VISITOR_HASHES);
        if (changed) persistQuietly();
    }

    public synchronized void recordAuditSuccess(String visitorHash, boolean planReused, int courses) {
        touch(visitorHash);
        state.totalAudits++;
        state.successfulAudits++;
        state.coursesParsed += Math.max(0, courses);
        if (planReused) state.planReuses++; else state.planUploads++;
        DailyStat daily = today();
        daily.audits++;
        daily.successes++;
        if (planReused) daily.planReuses++;
        persistQuietly();
    }

    public synchronized void recordAuditFailure(String visitorHash) {
        touch(visitorHash);
        state.totalAudits++;
        state.failedAudits++;
        DailyStat daily = today();
        daily.audits++;
        daily.failures++;
        persistQuietly();
    }

    public synchronized FeedbackView submitFeedback(String visitorHash, String type, String content,
                                                     String contact, String page) throws IOException {
        touch(visitorHash);
        if (state.feedback.size() >= MAX_FEEDBACK) {
            state.feedback.remove(0);
        }
        FeedbackItem item = new FeedbackItem();
        item.id = UUID.randomUUID().toString();
        item.type = clean(type, 20);
        item.content = cleanMultiline(content, 2_000);
        item.contact = clean(contact, 100);
        item.page = clean(page, 120);
        item.status = "new";
        item.createdAt = Instant.now().toString();
        item.visitorHash = visitorHash;
        state.feedback.add(item);
        today().feedback++;
        persist();
        return view(item);
    }

    public synchronized boolean updateFeedback(String id, String status) throws IOException {
        if (!"new".equals(status) && !"resolved".equals(status)) return false;
        for (FeedbackItem item : state.feedback) {
            if (item.id.equals(id)) {
                item.status = status;
                item.resolvedAt = "resolved".equals(status) ? Instant.now().toString() : "";
                persist();
                return true;
            }
        }
        return false;
    }

    public synchronized Dashboard dashboard() {
        prune();
        List<DailyView> daily = state.daily.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DailyView(entry.getKey(), entry.getValue().visitorHashes.size(),
                        entry.getValue().audits, entry.getValue().successes, entry.getValue().failures,
                        entry.getValue().planReuses, entry.getValue().feedback))
                .toList();
        List<FeedbackView> feedback = state.feedback.stream()
                .sorted((left, right) -> right.createdAt.compareTo(left.createdAt))
                .map(this::view).toList();
        return new Dashboard(state.visitorHashes.size(), state.totalAudits, state.successfulAudits,
                state.failedAudits, state.planReuses, state.planUploads, state.coursesParsed,
                daily, feedback);
    }

    private void touch(String visitorHash) {
        addBounded(state.visitorHashes, visitorHash, MAX_VISITOR_HASHES);
        addBounded(today().visitorHashes, visitorHash, MAX_DAILY_VISITOR_HASHES);
    }

    private DailyStat today() {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        return state.daily.computeIfAbsent(date, ignored -> new DailyStat());
    }

    private void prune() {
        String cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS - 1L).toString();
        state.daily.entrySet().removeIf(entry -> entry.getKey().compareTo(cutoff) < 0);
        trimOldest(state.visitorHashes, MAX_VISITOR_HASHES);
        state.daily.values().forEach(day -> {
            if (day.visitorHashes == null) day.visitorHashes = new LinkedHashSet<>();
            trimOldest(day.visitorHashes, MAX_DAILY_VISITOR_HASHES);
        });
    }

    private boolean addBounded(LinkedHashSet<String> values, String value, int limit) {
        if (values.contains(value)) return false;
        if (values.size() >= limit) values.remove(values.iterator().next());
        return values.add(value);
    }

    private void trimOldest(LinkedHashSet<String> values, int limit) {
        while (values.size() > limit) values.remove(values.iterator().next());
    }

    private FeedbackView view(FeedbackItem item) {
        return new FeedbackView(item.id, item.type, item.content, item.contact, item.page,
                item.status, item.createdAt, item.resolvedAt);
    }

    private State load() {
        if (!Files.isRegularFile(dataFile)) return new State();
        try {
            State loaded = MAPPER.readValue(dataFile.toFile(), State.class);
            return loaded == null ? new State() : loaded.normalize();
        } catch (Exception err) {
            log.error("运营统计文件读取失败，已使用空统计", err);
            return new State();
        }
    }

    private void persistQuietly() {
        try {
            persist();
        } catch (IOException err) {
            log.error("运营统计保存失败", err);
        }
    }

    private void persist() throws IOException {
        prune();
        Files.createDirectories(dataFile.getParent());
        Path temp = Files.createTempFile(dataFile.getParent(), "operations-", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), state);
            try {
                Files.move(temp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path resolveDataRoot() {
        String custom = System.getenv("CREDIT_AUDIT_DATA_DIR");
        if (custom != null && !custom.isBlank()) return Path.of(custom).toAbsolutePath().normalize();
        String catalinaBase = System.getProperty("catalina.base", System.getProperty("java.io.tmpdir"));
        return Path.of(catalinaBase, "data", "student_system", "credit-audit").toAbsolutePath().normalize();
    }

    private String clean(String value, int max) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private String cleanMultiline(String value, int max) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class State {
        public long totalAudits;
        public long successfulAudits;
        public long failedAudits;
        public long planReuses;
        public long planUploads;
        public long coursesParsed;
        public LinkedHashSet<String> visitorHashes = new LinkedHashSet<>();
        public LinkedHashMap<String, DailyStat> daily = new LinkedHashMap<>();
        public ArrayList<FeedbackItem> feedback = new ArrayList<>();

        State normalize() {
            if (visitorHashes == null) visitorHashes = new LinkedHashSet<>();
            if (daily == null) daily = new LinkedHashMap<>();
            if (feedback == null) feedback = new ArrayList<>();
            daily.values().forEach(item -> {
                if (item.visitorHashes == null) item.visitorHashes = new LinkedHashSet<>();
            });
            return this;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DailyStat {
        public long audits;
        public long successes;
        public long failures;
        public long planReuses;
        public long feedback;
        public LinkedHashSet<String> visitorHashes = new LinkedHashSet<>();
    }

    public static final class FeedbackItem {
        public String id = "";
        public String type = "";
        public String content = "";
        public String contact = "";
        public String page = "";
        public String status = "new";
        public String createdAt = "";
        public String resolvedAt = "";
        public String visitorHash = "";
    }

    public record DailyView(String date, int users, long audits, long successes, long failures,
                            long planReuses, long feedback) {}

    public record FeedbackView(String id, String type, String content, String contact, String page,
                               String status, String createdAt, String resolvedAt) {}

    public record Dashboard(int users, long totalAudits, long successfulAudits, long failedAudits,
                            long planReuses, long planUploads, long coursesParsed,
                            List<DailyView> daily, List<FeedbackView> feedback) {}
}
