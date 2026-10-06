package servlet;

import pojo.Result;
import service.CreditAuditService;
import service.CreditPlanRegistryService;
import service.RequestRateLimiter;
import util.SecurityUtil;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/** 运营后台上传并登记人才培养方案。 */
@WebServlet("/api/credit-audit/admin/plans")
@MultipartConfig(fileSizeThreshold = 0, maxFileSize = CreditAuditService.MAX_UPLOAD_BYTES,
        maxRequestSize = 9 * 1024 * 1024)
public class CreditPlanAdminUploadServlet extends CreditAuditApiServlet {
    private static final CreditAuditService SERVICE = new CreditAuditService();
    private static final CreditPlanRegistryService PLANS = CreditPlanRegistryService.getInstance();
    private static final Set<String> MULTIPART_FIELDS = Set.of(
            "school", "major", "cohort", "replacePlanId", "planFile");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        write(response, 200, Result.success(PLANS.listManaged()));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        prepare(response);
        if (!authorized(request, response)) return;
        if (!trustedWriteRequest(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String rateKey = "credit-admin-plan:" + SecurityUtil.sha256(String.valueOf(request.getRemoteAddr()));
        if (RequestRateLimiter.isBlocked(rateKey, 20, Duration.ofHours(1))
                || !RequestRateLimiter.recordFailure(rateKey, Duration.ofHours(1))) {
            write(response, 429, Result.fail(429, "上传过于频繁，请稍后再试"));
            return;
        }
        try {
            validateMultipart(request, MULTIPART_FIELDS, 5);
            String school = clean(request.getParameter("school"), 60);
            String major = clean(request.getParameter("major"), 80);
            String cohort = clean(request.getParameter("cohort"), 4);
            String replacePlanId = clean(request.getParameter("replacePlanId"), 80);
            if (school.isBlank() || major.isBlank() || !cohort.matches("20\\d{2}")) {
                write(response, 400, Result.fail(400, "请填写学校、专业和四位入学年份"));
                return;
            }
            Part part = request.getPart("planFile");
            if (part == null || part.getSize() <= 0) {
                write(response, 400, Result.fail(400, "请选择人才培养方案文件"));
                return;
            }
            String originalName = safeSubmittedName(part);
            byte[] bytes;
            try (var input = part.getInputStream()) {
                bytes = input.readNBytes(CreditAuditService.MAX_UPLOAD_BYTES + 1);
            } finally {
                try { part.delete(); } catch (IOException ignored) { }
            }
            if (bytes.length > CreditAuditService.MAX_UPLOAD_BYTES) {
                write(response, 413, Result.fail(413, "文件不能超过 8MB"));
                return;
            }
            var extraction = SERVICE.parsePlan(bytes, originalName);
            if (extraction.modules().isEmpty()) {
                write(response, 400, Result.fail(400, "未识别到培养方案一级学分板块，请检查文件"));
                return;
            }
            String hash = PLANS.sha256(bytes);
            String canonicalName = PLANS.canonicalSourceName(school, major, cohort, originalName);
            var plan = replacePlanId.isBlank()
                    ? PLANS.saveProvisional(school, major, cohort, hash, canonicalName, extraction.modules())
                    : PLANS.replace(replacePlanId, school, major, cohort, hash, canonicalName, extraction.modules());
            write(response, 200, Result.success(plan));
        } catch (IllegalStateException | ServletException error) {
            write(response, 413, Result.fail(413, "文件不能超过 8MB"));
        } catch (IOException error) {
            write(response, 400, Result.fail(400,
                    safeMessage(error.getMessage(), "培养方案无法安全解析", 120)));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        if (!sameOrigin(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String id = clean(request.getParameter("id"), 80);
        if (id.isBlank()) {
            write(response, 400, Result.fail(400, "缺少培养方案编号"));
            return;
        }
        try {
            write(response, 200, Result.success(PLANS.deactivate(id)));
        } catch (IOException error) {
            write(response, 404, Result.fail(404,
                    safeMessage(error.getMessage(), "培养方案记录不存在", 120)));
        }
    }
}
