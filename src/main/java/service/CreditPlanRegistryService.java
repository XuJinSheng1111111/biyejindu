package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 共享培养方案规则库。只保存结构化规则和文件哈希，不保存用户上传的原文件。
 */
@Slf4j
public final class CreditPlanRegistryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RECORDS = 5_000;
    private static final String MATH_HASH = "363d62d473cad3c574ff1e0fa09cfbe3e01b957eb5468b4d9a6260a46eae93b3";
    private static final String INFO_HASH = "0881d7e0f08bb5f4b4100755d5a8ae1e6bebc20122132f3f3fb7cbec35bf5408";
    private static final CreditPlanRegistryService INSTANCE = new CreditPlanRegistryService();

    private final Path registryFile;
    private final Path statusFile;
    private final List<PlanRecord> records = new ArrayList<>();
    private final Set<String> inactivePlanIds = new LinkedHashSet<>();

    private CreditPlanRegistryService() {
        this(resolveRegistryFile());
    }

    CreditPlanRegistryService(Path registryFile) {
        this.registryFile = registryFile.toAbsolutePath().normalize();
        this.statusFile = this.registryFile.resolveSibling("plan-status.json");
        loadPersisted();
        loadStatuses();
        seedVerifiedPlans();
    }

    public static CreditPlanRegistryService getInstance() {
        return INSTANCE;
    }

    public synchronized PlanCheckResult check(String school, String major, String cohort, String hash) {
        String normalizedHash = normalizeHash(hash);
        PlanRecord byHash = records.stream().filter(item -> isActive(item.id()) && item.sha256().equals(normalizedHash)
                && sameIdentity(item, school, major, cohort, true)).findFirst().orElse(null);
        if (byHash != null) {
            return new PlanCheckResult(true, true, byHash, "服务器已有相同方案，本次无需上传培养方案");
        }
        PlanRecord sameIdentity = records.stream()
                .filter(item -> isActive(item.id()) && sameIdentity(item, school, major, cohort, false))
                .findFirst().orElse(null);
        if (sameIdentity != null) {
            return new PlanCheckResult(false, false, sameIdentity, "服务器存在同专业年级的其他版本，请上传当前文件核对版本");
        }
        return new PlanCheckResult(false, false, null, "服务器暂无该方案，需要上传解析");
    }

    public synchronized PlanRecord saveProvisional(String school, String major, String cohort, String hash,
                                                     String sourceName,
                                                     CreditAuditService.PlanExtraction extraction) throws IOException {
        validateIdentity(school, major, cohort);
        String normalizedHash = normalizeHash(hash);
        PlanRecord existing = records.stream().filter(item -> item.sha256().equals(normalizedHash)
                && sameIdentity(item, school, major, cohort, true)).findFirst().orElse(null);
        if (existing != null) return existing;
        if (records.size() >= MAX_RECORDS) throw new IOException("培养方案规则库已达容量上限");
        PlanRecord record = new PlanRecord(
                UUID.randomUUID().toString(), clean(school, 60), clean(major, 80), clean(cohort, 20),
                normalizedHash, clean(sourceName, 120), false, Instant.now().toString(),
                List.copyOf(extraction.modules()), extraction.totalCredits(), extraction.requiredCredits(),
                extraction.electiveCredits(), List.copyOf(extraction.subRequirements()),
                List.copyOf(extraction.completionRequirements())
        );
        records.add(record);
        persist();
        return record;
    }

    public synchronized PlanRecord findById(String id) {
        if (id == null || id.length() > 80) return null;
        return records.stream().filter(item -> isActive(item.id()) && item.id().equals(id)).findFirst().orElse(null);
    }

    public List<String> validationWarnings(PlanRecord plan) {
        if (plan != null && MATH_HASH.equals(plan.sha256())) {
            return List.of("培养方案原文存在矛盾：正文写开设课程总学分为 218.5，表 3 各项相加及合计为 220.5；"
                    + "毕业进度仍按表 6 的最低 165 学分计算，开设课程总量请向学校确认。");
        }
        return List.of();
    }

    /** 返回前端可直接选择的方案元数据，不包含上传原文件。 */
    public synchronized List<PlanSummary> listAvailable() {
        return records.stream()
                .filter(item -> isActive(item.id()))
                .filter(item -> item.cohort() != null && item.cohort().matches("20\\d{2}"))
                .sorted((left, right) -> Boolean.compare(right.verified(), left.verified()))
                .map(item -> new PlanSummary(item.id(), item.school(), item.major(), item.cohort(),
                        item.sha256(), item.sourceName(), item.verified(), item.createdAt()))
                .toList();
    }

    /** 返回运营后台可管理的全部方案，包括已下架记录。 */
    public synchronized List<PlanAdminSummary> listManaged() {
        return records.stream()
                .filter(item -> item.cohort() != null && item.cohort().matches("20\\d{2}"))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .map(item -> adminSummary(item, isActive(item.id())))
                .toList();
    }

    public synchronized PlanAdminSummary deactivate(String id) throws IOException {
        PlanRecord plan = findAnyById(id);
        if (plan == null) throw new IOException("培养方案不存在");
        inactivePlanIds.add(plan.id());
        persistStatuses();
        return adminSummary(plan, false);
    }

    public synchronized PlanRecord replace(String id, String school, String major, String cohort, String hash,
                                            String sourceName,
                                            CreditAuditService.PlanExtraction extraction) throws IOException {
        PlanRecord previous = findAnyById(id);
        if (previous == null) throw new IOException("需要替换的培养方案不存在");
        validateIdentity(school, major, cohort);
        if (records.size() >= MAX_RECORDS) throw new IOException("培养方案规则库已达容量上限");
        PlanRecord replacement = new PlanRecord(
                UUID.randomUUID().toString(), clean(school, 60), clean(major, 80), clean(cohort, 20),
                normalizeHash(hash), clean(sourceName, 120), false, Instant.now().toString(),
                List.copyOf(extraction.modules()), extraction.totalCredits(), extraction.requiredCredits(),
                extraction.electiveCredits(), List.copyOf(extraction.subRequirements()),
                List.copyOf(extraction.completionRequirements())
        );
        boolean wasInactive = inactivePlanIds.contains(previous.id());
        records.add(replacement);
        inactivePlanIds.add(previous.id());
        try {
            persist();
            persistStatuses();
        } catch (IOException error) {
            records.remove(replacement);
            if (!wasInactive) inactivePlanIds.remove(previous.id());
            throw error;
        }
        return replacement;
    }

    public String canonicalSourceName(String school, String major, String cohort, String originalName) {
        String extension = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                extension = originalName.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^.a-z0-9]", "");
            }
        }
        return clean(school, 60) + "-" + clean(major, 80) + "-" + clean(cohort, 4) + "级-人才培养方案" + extension;
    }

    public String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException err) {
            throw new IOException("服务器不支持安全哈希算法", err);
        }
    }

    public boolean constantTimeHashEquals(String expected, String actual) {
        try {
            return MessageDigest.isEqual(normalizeHash(expected).getBytes(StandardCharsets.US_ASCII),
                    normalizeHash(actual).getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException err) {
            return false;
        }
    }

    private void seedVerifiedPlans() {
        if (records.stream().noneMatch(item -> item.sha256().equals(MATH_HASH))) records.add(mathPlan());
        if (records.stream().noneMatch(item -> item.sha256().equals(INFO_HASH))) records.add(infoPlan());
    }

    private PlanRecord mathPlan() {
        return verified("builtin-math-070101-2024", "数学与应用数学", MATH_HASH, 134.5, 30.5,
                List.of(module("通识必修", 43.5), module("通识选修", 15), module("学科基础必修", 37),
                        module("专业必修", 54), module("专业选修", 15.5)));
    }

    private PlanRecord infoPlan() {
        return verified("builtin-info-070102-2024", "信息与计算科学", INFO_HASH, 127, 38,
                List.of(module("通识必修", 43.5), module("通识选修", 15), module("学科基础必修", 42),
                        module("专业必修", 41.5), module("专业选修", 23)));
    }

    private PlanRecord verified(String id, String major, String hash, double required, double elective,
                                List<CreditAuditService.ModuleRequirement> modules) {
        List<SubRequirement> sub = List.of(
                new SubRequirement("通识选修", "思维与方法", 2),
                new SubRequirement("通识选修", "艺术与审美", 2),
                new SubRequirement("通识选修", "生命与健康", 1),
                new SubRequirement("通识选修", "语言与文化", 1),
                new SubRequirement("通识选修", "创新与创业实践", 2),
                new SubRequirement("通识选修", "学科前沿讲座", 1)
        );
        List<CompletionRequirement> completion = List.of(
                new CompletionRequirement("国家安全教育", 1, false),
                new CompletionRequirement("大学生健康与安全教育", 1, false),
                new CompletionRequirement("劳动", 2, false),
                new CompletionRequirement("职业生涯规划", 0.5, false),
                new CompletionRequirement("毕业生就业指导", 0.5, false)
        );
        return new PlanRecord(id, "韶关学院", major, "2024", hash,
                "韶关学院-" + major + "-2024级-人才培养方案.docx", true, Instant.now().toString(), modules,
                165, required, elective, sub, completion);
    }

    private CreditAuditService.ModuleRequirement module(String name, double credits) {
        return new CreditAuditService.ModuleRequirement(name, credits,
                "毕业标准表：" + name + " " + credits + " 学分", "已核对");
    }

    private PlanRecord copyForIdentity(PlanRecord source, String school, String major, String cohort) {
        return new PlanRecord(source.id(), clean(school, 60), clean(major, 80), clean(cohort, 20),
                source.sha256(), source.sourceName(), source.verified(), source.createdAt(), source.modules(),
                source.totalCredits(), source.requiredCredits(), source.electiveCredits(),
                source.subRequirements(), source.completionRequirements());
    }

    private boolean sameIdentity(PlanRecord item, String school, String major, String cohort, boolean requireHashIdentity) {
        boolean base = normalize(item.school()).equals(normalize(school))
                && normalize(item.major()).equals(normalize(major));
        if (!base) return false;
        if (requireHashIdentity && item.cohort().equals("以文件为准")) return true;
        return normalize(item.cohort()).equals(normalize(cohort));
    }

    private void validateIdentity(String school, String major, String cohort) throws IOException {
        if (clean(school, 60).isBlank() || clean(major, 80).isBlank() || !clean(cohort, 20).matches("20\\d{2}")) {
            throw new IOException("学校、专业不能为空，入学年级须为四位年份");
        }
    }

    private String normalizeHash(String hash) {
        String value = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("文件哈希不合法");
        return value;
    }

    private String clean(String value, int max) {
        String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private String normalize(String value) {
        return clean(value, 100).toLowerCase(Locale.ROOT).replaceAll("[\\s_—–\\-：:（）()【】\\[\\]]", "");
    }

    private static Path resolveRegistryFile() {
        String custom = System.getenv("CREDIT_AUDIT_DATA_DIR");
        Path root;
        if (custom != null && !custom.isBlank()) {
            root = Path.of(custom);
        } else {
            String catalinaBase = System.getProperty("catalina.base", System.getProperty("java.io.tmpdir"));
            root = Path.of(catalinaBase, "data", "student_system", "credit-audit");
        }
        return root.toAbsolutePath().normalize().resolve("plans.json");
    }

    private void loadPersisted() {
        if (!Files.isRegularFile(registryFile)) return;
        try {
            List<PlanRecord> loaded = MAPPER.readValue(registryFile.toFile(), new TypeReference<>() {});
            if (loaded.size() <= MAX_RECORDS) records.addAll(loaded.stream().filter(Objects::nonNull).toList());
        } catch (Exception err) {
            log.error("培养方案规则库读取失败，已忽略损坏文件", err);
        }
    }

    private void loadStatuses() {
        if (!Files.isRegularFile(statusFile)) return;
        try {
            List<String> loaded = MAPPER.readValue(statusFile.toFile(), new TypeReference<>() {});
            loaded.stream().filter(Objects::nonNull).filter(id -> id.length() <= 80).forEach(inactivePlanIds::add);
        } catch (Exception err) {
            log.error("培养方案上下架状态读取失败，已按全部上架处理", err);
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(registryFile.getParent());
        Path temp = Files.createTempFile(registryFile.getParent(), "plans-", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), records.stream().filter(item -> !item.id().startsWith("builtin-")).toList());
            try {
                Files.move(temp, registryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(temp, registryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void persistStatuses() throws IOException {
        Files.createDirectories(statusFile.getParent());
        Path temp = Files.createTempFile(statusFile.getParent(), "plan-status-", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), inactivePlanIds);
            try {
                Files.move(temp, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(temp, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private boolean isActive(String id) {
        return !inactivePlanIds.contains(id);
    }

    private PlanRecord findAnyById(String id) {
        if (id == null || id.length() > 80) return null;
        return records.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    private PlanAdminSummary adminSummary(PlanRecord item, boolean active) {
        return new PlanAdminSummary(item.id(), item.school(), item.major(), item.cohort(), item.sha256(),
                item.sourceName(), item.verified(), active, item.createdAt());
    }

    public record SubRequirement(String parentModule, String name, double requiredCredits) {}
    public record CompletionRequirement(String courseName, double nominalCredits, boolean countsTowardTotal) {}
    public record PlanRecord(String id, String school, String major, String cohort, String sha256,
                             String sourceName, boolean verified, String createdAt,
                             List<CreditAuditService.ModuleRequirement> modules,
                             double totalCredits, double requiredCredits, double electiveCredits,
                             List<SubRequirement> subRequirements,
                             List<CompletionRequirement> completionRequirements) {}
    public record PlanCheckResult(boolean found, boolean skipUpload, PlanRecord plan, String message) {}
    public record PlanSummary(String id, String school, String major, String cohort, String sha256,
                              String sourceName, boolean verified, String createdAt) {}
    public record PlanAdminSummary(String id, String school, String major, String cohort, String sha256,
                                   String sourceName, boolean verified, boolean active, String createdAt) {}
}
