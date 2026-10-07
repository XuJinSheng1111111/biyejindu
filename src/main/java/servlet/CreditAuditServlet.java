package servlet;

import lombok.extern.slf4j.Slf4j;
import pojo.Result;
import service.CreditAuditService;
import service.CreditAuditOperationsService;
import service.CreditPlanRegistryService;
import service.DeepSeekClient;
import service.DeepSeekVerificationService;
import service.RequestRateLimiter;
import service.ScoreArchiveParser;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 免登录毕业学分自查接口。
 * 个人成绩只在内存处理；培养方案只保存结构化规则和哈希，不保存原文件。
 */
@Slf4j
@WebServlet("/api/credit-audit/parse")
@MultipartConfig(fileSizeThreshold = 0, maxFileSize = CreditAuditService.MAX_UPLOAD_BYTES,
        maxRequestSize = 17 * 1024 * 1024)
public class CreditAuditServlet extends CreditAuditApiServlet {

    private static final CreditAuditService SERVICE = new CreditAuditService();
    private static final ScoreArchiveParser ARCHIVE_PARSER = new ScoreArchiveParser(SERVICE);
    private static final CreditPlanRegistryService PLANS = CreditPlanRegistryService.getInstance();
    private static final CreditAuditOperationsService OPERATIONS = CreditAuditOperationsService.getInstance();
    private static final DeepSeekVerificationService AI_VERIFICATION = DeepSeekVerificationService.getInstance();
    private static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    private static final Duration DAILY_RATE_WINDOW = Duration.ofDays(1);
    private static final Set<String> MULTIPART_FIELDS = Set.of(
            "school", "major", "cohort", "planHash", "planId", "planFile", "scoreFile");
    private static final ThreadPoolExecutor PARSERS = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
            runnable -> {
                Thread thread = new Thread(runnable, "credit-audit-parser");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        prepare(resp);
        if (!isSameOrigin(req)) {
            write(resp, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String visitor = OPERATIONS.identify(req, resp);
        OPERATIONS.recordVisit(visitor);
        if (!allow("credit-plan-check:" + clientAddress(req), 60)) {
            write(resp, 429, Result.fail(429, "查询过于频繁，请稍后再试"));
            return;
        }
        try {
            var result = PLANS.check(req.getParameter("school"), req.getParameter("major"),
                    req.getParameter("cohort"), req.getParameter("hash"));
            write(resp, 200, Result.success(result));
        } catch (IllegalArgumentException err) {
            write(resp, 400, Result.fail(400, "培养方案指纹不合法"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        prepare(resp);
        if (!trustedWriteRequest(req)) {
            write(resp, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String visitor = OPERATIONS.identify(req, resp);
        OPERATIONS.recordVisit(visitor);
        String client = clientAddress(req);
        if (!allow("credit-audit:" + client, 15)
                || !allow("credit-audit-daily:" + client, 120, DAILY_RATE_WINDOW)) {
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 429, Result.fail(429, "操作过于频繁，请稍后再试"));
            return;
        }

        Future<AuditResponse> future = null;
        try {
            validateMultipart(req, MULTIPART_FIELDS, 7);
            Part scorePart = req.getPart("scoreFile");
            if (scorePart == null || scorePart.getSize() <= 0) {
                OPERATIONS.recordAuditFailure(visitor);
                write(resp, 400, Result.fail(400, "请选择个人成绩文件"));
                return;
            }
            String scoreName = safeSubmittedName(scorePart);
            byte[] scoreBytes = readBounded(scorePart);
            String planId = clean(req.getParameter("planId"), 80);
            String planHash = clean(req.getParameter("planHash"), 64);
            String school = clean(req.getParameter("school"), 60);
            String major = clean(req.getParameter("major"), 80);
            String cohort = clean(req.getParameter("cohort"), 20);

            CreditPlanRegistryService.PlanRecord cachedPlan = planId.isBlank() ? null : PLANS.findById(planId);
            byte[] planBytes = null;
            String planName = null;
            if (cachedPlan != null) {
                if (!planHash.isBlank() && !PLANS.constantTimeHashEquals(cachedPlan.sha256(), planHash)) {
                    OPERATIONS.recordAuditFailure(visitor);
                    write(resp, 409, Result.fail(409, "培养方案版本已变化，请重新选择文件"));
                    return;
                }
            } else {
                Part planPart = req.getPart("planFile");
                if (planPart == null || planPart.getSize() <= 0) {
                    OPERATIONS.recordAuditFailure(visitor);
                    write(resp, 400, Result.fail(400, "服务器暂无该方案，请上传培养方案"));
                    return;
                }
                planName = safeSubmittedName(planPart);
                planBytes = readBounded(planPart);
                String actualHash = PLANS.sha256(planBytes);
                if (!PLANS.constantTimeHashEquals(actualHash, planHash)) {
                    OPERATIONS.recordAuditFailure(visitor);
                    write(resp, 400, Result.fail(400, "培养方案文件校验失败，请重新选择"));
                    return;
                }
            }

            byte[] finalPlanBytes = planBytes;
            String finalPlanName = planName;
            CreditPlanRegistryService.PlanRecord finalCachedPlan = cachedPlan;
            future = PARSERS.submit(() -> parse(finalCachedPlan, finalPlanBytes, finalPlanName,
                    planHash, school, major, cohort, scoreBytes, scoreName));
            AuditResponse result = future.get(110, TimeUnit.SECONDS);
            OPERATIONS.recordAuditSuccess(visitor, result.planReused(), result.courses().size());
            write(resp, 200, Result.success(result));
        } catch (TimeoutException err) {
            if (future != null) future.cancel(true);
            log.warn("毕业学分文件解析超时");
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 408, Result.fail(408, "文件解析超时，请稍后重试"));
        } catch (java.util.concurrent.RejectedExecutionException err) {
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 503, Result.fail(503, "解析任务较多，请稍后再试"));
        } catch (ExecutionException err) {
            OPERATIONS.recordAuditFailure(visitor);
            Throwable cause = err.getCause();
            if (cause instanceof IOException io) {
                log.info("毕业学分自查文件解析失败：{}", io.getMessage());
                write(resp, 400, Result.fail(400, safeError(io.getMessage())));
                return;
            }
            log.warn("毕业学分自查异步解析异常", cause);
            write(resp, 400, Result.fail(400, "文件无法安全解析，请检查格式后重试"));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 503, Result.fail(503, "服务暂时中断，请重试"));
        } catch (IllegalStateException | ServletException err) {
            log.warn("毕业学分自查文件超过上传限制");
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 413, Result.fail(413, "单个文件不能超过 8MB"));
        } catch (IOException err) {
            log.info("毕业学分自查文件解析失败：{}", err.getMessage());
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 400, Result.fail(400, safeError(err.getMessage())));
        } catch (RuntimeException err) {
            log.warn("毕业学分自查解析异常", err);
            OPERATIONS.recordAuditFailure(visitor);
            write(resp, 400, Result.fail(400, "文件无法安全解析，请检查格式后重试"));
        }
    }

    private AuditResponse parse(CreditPlanRegistryService.PlanRecord cachedPlan, byte[] planBytes, String planName,
                                String planHash, String school, String major, String cohort,
                                byte[] scoreBytes, String scoreName) throws IOException {
        List<String> warnings = new ArrayList<>();
        CreditPlanRegistryService.PlanRecord plan = cachedPlan;
        boolean reused = plan != null;
        if (plan == null) {
            var known = PLANS.check(school, major, cohort, planHash);
            if (known.found() && known.plan() != null) {
                plan = known.plan();
                reused = true;
            } else {
                var parsedPlan = SERVICE.parsePlan(planBytes, planName);
                warnings.addAll(parsedPlan.warnings());
                String canonicalName = PLANS.canonicalSourceName(school, major, cohort, planName);
                plan = new CreditPlanRegistryService.PlanRecord("", school, major, cohort, planHash,
                        canonicalName, false, Instant.now().toString(), parsedPlan.modules(),
                        parsedPlan.totalCredits(), parsedPlan.requiredCredits(), parsedPlan.electiveCredits(),
                        parsedPlan.subRequirements(), parsedPlan.completionRequirements());
                warnings.add("该培养方案为自动提取版本，需逐项核对后再使用结果。");
            }
        }
        warnings.addAll(PLANS.validationWarnings(plan));
        List<CreditAuditService.CourseRecord> scoreCourses;
        List<String> scoreWarnings;
        if (isScoreArchive(scoreName)) {
            var archiveScores = ARCHIVE_PARSER.parse(scoreBytes);
            scoreCourses = archiveScores.courses();
            scoreWarnings = archiveScores.warnings();
        } else {
            var documentScores = SERVICE.parseCourses(scoreBytes, scoreName);
            scoreCourses = documentScores.courses();
            scoreWarnings = documentScores.warnings();
        }
        warnings.addAll(scoreWarnings);
        DeepSeekClient.AiReview aiReview = AI_VERIFICATION.verify(plan, scoreCourses, warnings);
        return new AuditResponse(plan, plan.modules(), scoreCourses, warnings, scoreName, reused, aiReview);
    }

    private boolean allow(String key, int limit) {
        return allow(key, limit, RATE_WINDOW);
    }

    private boolean allow(String key, int limit, Duration window) {
        return !RequestRateLimiter.isBlocked(key, limit, window)
                && RequestRateLimiter.recordFailure(key, window);
    }

    private byte[] readBounded(Part part) throws IOException {
        if (part.getSize() > CreditAuditService.MAX_UPLOAD_BYTES) throw new IOException("单个文件不能超过 8MB");
        try (var input = part.getInputStream()) {
            byte[] bytes = input.readNBytes(CreditAuditService.MAX_UPLOAD_BYTES + 1);
            if (bytes.length > CreditAuditService.MAX_UPLOAD_BYTES) throw new IOException("单个文件不能超过 8MB");
            return bytes;
        } finally {
            try { part.delete(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void destroy() {
        PARSERS.shutdownNow();
        super.destroy();
    }

    private boolean isScoreArchive(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    private String safeError(String message) {
        if (message == null || message.isBlank() || message.length() > 120) return "文件无法安全解析，请检查格式后重试";
        return message.replaceAll("[\\r\\n]", " ");
    }

    public record AuditResponse(CreditPlanRegistryService.PlanRecord plan,
                                List<CreditAuditService.ModuleRequirement> modules,
                                List<CreditAuditService.CourseRecord> courses,
                                List<String> warnings, String scoreFileName, boolean planReused,
                                DeepSeekClient.AiReview aiVerification) {}
}
