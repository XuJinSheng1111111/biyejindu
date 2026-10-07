package service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AI 二次核对编排服务。失败时阻止本次核对完成。 */
public final class DeepSeekVerificationService {

    private static final DeepSeekVerificationService INSTANCE = new DeepSeekVerificationService(
            DeepSeekConfigService.getInstance(), new DeepSeekClient());
    private final DeepSeekConfigService configService;
    private final DeepSeekClient client;

    DeepSeekVerificationService(DeepSeekConfigService configService, DeepSeekClient client) {
        this.configService = configService;
        this.client = client;
    }

    public static DeepSeekVerificationService getInstance() {
        return INSTANCE;
    }

    public DeepSeekClient.AiReview verify(CreditPlanRegistryService.PlanRecord plan,
                                          List<CreditAuditService.CourseRecord> courses,
                                          List<String> localWarnings) throws IOException {
        if (plan == null || plan.modules() == null || plan.modules().isEmpty()
                || courses == null || courses.isEmpty()) {
            throw new IOException("本地解析结果不完整，无法进行 AI 二次核对");
        }
        try {
            DeepSeekClient.AiReview review = client.verify(configService.runtimeConfig(), plan, courses, localWarnings);
            return mergeMandatoryHumanChecks(review, plan, courses, localWarnings);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("AI 二次核对已中断，请重试", error);
        } catch (IllegalStateException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private DeepSeekClient.AiReview mergeMandatoryHumanChecks(DeepSeekClient.AiReview review,
                                                               CreditPlanRegistryService.PlanRecord plan,
                                                               List<CreditAuditService.CourseRecord> courses,
                                                               List<String> localWarnings) {
        List<DeepSeekClient.AiIssue> merged = new ArrayList<>();
        List<CreditPlanRegistryService.CompletionRequirement> separate = safeList(plan.completionRequirements())
                .stream().filter(rule -> !rule.countsTowardTotal()).toList();
        if (!separate.isEmpty()) {
            List<String> completed = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (var rule : separate) {
                boolean found = courses.stream().anyMatch(course -> course.passed()
                        && sameCourse(course.name(), rule.courseName()));
                (found ? completed : missing).add(rule.courseName());
            }
            double credits = separate.stream().mapToDouble(
                    CreditPlanRegistryService.CompletionRequirement::nominalCredits).sum();
            String detail = "培养方案列有 " + separate.size() + " 门共 " + number(credits)
                    + " 学分的课程必须完成，但不计入 " + number(plan.totalCredits()) + " 个毕业总学分。"
                    + "成绩文件已识别：" + joined(completed) + "；未识别：" + joined(missing)
                    + "。请人工核对课程记录及学校计分口径。";
            merged.add(new DeepSeekClient.AiIssue("需核对", "不计入总学分课程需单独确认", detail));
        }
        safeList(localWarnings).stream()
                .filter(this::isCriticalPlanWarning)
                .forEach(message -> merged.add(new DeepSeekClient.AiIssue(
                        "需核对", "培养方案原文数值需确认", message)));
        if (!plan.verified()) {
            merged.add(new DeepSeekClient.AiIssue("需核对", "培养方案规则为自动提取",
                    "当前方案不是已核对版本，请逐项对照毕业标准表、课程安排表和特殊说明后再采用计算结果。"));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<DeepSeekClient.AiIssue> issues = new ArrayList<>();
        for (DeepSeekClient.AiIssue issue : merged) addUnique(issues, seen, issue);
        for (DeepSeekClient.AiIssue issue : safeList(review.issues())) addUnique(issues, seen, issue);
        if (issues.size() > 12) issues = new ArrayList<>(issues.subList(0, 12));
        String summary = merged.isEmpty() ? review.summary()
                : "DeepSeek 二次核对已完成；以下计分口径必须由人工确认。";
        String confidence = merged.isEmpty() ? review.confidence() : "中";
        return new DeepSeekClient.AiReview(review.completed(), review.model(), review.reasoningEffort(),
                summary, confidence, List.copyOf(issues), review.privacyScope());
    }

    private void addUnique(List<DeepSeekClient.AiIssue> target, Set<String> seen, DeepSeekClient.AiIssue issue) {
        String key = normalize(issue.title()) + "|" + normalize(issue.detail());
        if (seen.add(key)) target.add(issue);
    }

    private boolean isCriticalPlanWarning(String message) {
        String value = normalize(message);
        return value.contains("原文存在矛盾") || value.contains("不一致")
                || value.contains("自动提取版本") || value.contains("未自动识别");
    }

    private boolean sameCourse(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return !a.isBlank() && !b.isBlank() && (a.contains(b) || b.contains(a));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_—–\\-：:（）()【】\\[\\]]", "");
    }

    private String joined(List<String> values) {
        return values.isEmpty() ? "无" : String.join("、", values);
    }

    private String number(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
