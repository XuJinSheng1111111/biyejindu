package service;

import lombok.extern.slf4j.Slf4j;
import mapper.AiSummaryMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.AiSummary;
import pojo.StudentScore;
import util.SqlSessionFactoryUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 学业总结业务层。
 * 单学期总结：成绩确认导入成功后生成，并覆盖同学期旧总结。
 * 全学期总结：前端点击“生成整体学业总结”后生成，作为 type=all 保存。
 */
@Slf4j
public class ScoreSummaryService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();
    private static final StudentScoreService studentScoreService = new StudentScoreService();

    /**
     * 查询单学期已保存的规则统计总结。
     */
    public String getSingleSummary(int studentInfoId, String schoolYear, String term) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            AiSummaryMapper mapper = sqlSession.getMapper(AiSummaryMapper.class);
            AiSummary summary = mapper.selectOne(studentInfoId, schoolYear, term, "single");
            return summary == null ? null : summary.getSummaryText();
        } catch (Exception err) {
            log.error("查询单学期学业总结失败，studentInfoId={}, schoolYear={}, term={}",
                    studentInfoId, schoolYear, term, err);
            return null;
        }
    }

    /**
     * 基于某个学期的全部成绩生成单学期总结，并覆盖同学期旧文本。
     */
    public String generateSingleSummary(List<StudentScore> termScores) {
        if (termScores == null || termScores.isEmpty()) {
            return null;
        }

        StudentScore first = termScores.get(0);
        String schoolYear = first.getSchoolYear();
        String term = first.getTerm();

        if (schoolYear == null || term == null) {
            log.error("生成单学期总结失败：学年或学期为空");
            return null;
        }

        try {
            String summaryText = buildSummary(termScores,
                    schoolYear + "学年" + term + "学期");
            saveSummary(first.getStudentInfoId(), schoolYear, term, summaryText, "single");
            return summaryText;
        } catch (Exception err) {
            log.error("生成单学期学业总结失败", err);
            return null;
        }
    }

    /**
     * 基于学生全部成绩生成跨学期总结。
     * 该方法只在“生成整体学业总结”按钮触发时调用。
     */
    public String generateAllSummary(int studentInfoId) {
        List<StudentScore> allScores = studentScoreService.queryAllByStudentInfoId(studentInfoId);
        if (allScores == null || allScores.size() < 2 || countTerms(allScores) < 2) {
            return null;
        }

        try {
            String summaryText = buildSummary(allScores, "全部学期");
            saveSummary(studentInfoId, "ALL", "ALL", summaryText, "all");
            return summaryText;
        } catch (Exception err) {
            log.error("生成全学期综合学业总结失败，studentInfoId={}", studentInfoId, err);
            return null;
        }
    }

    private int countTerms(List<StudentScore> scores) {
        Set<String> terms = new HashSet<>();
        for (StudentScore score : scores) {
            if (score.getSchoolYear() != null && score.getTerm() != null) {
                terms.add(score.getSchoolYear() + "-" + score.getTerm());
            }
        }
        return terms.size();
    }

    private String buildSummary(List<StudentScore> scores, String rangeLabel) {
        long gradedCount = scores.stream().filter(score -> score.getTotalScore() != null).count();
        double average = scores.stream().filter(score -> score.getTotalScore() != null)
                .mapToDouble(StudentScore::getTotalScore).average().orElse(0);
        double credits = scores.stream().filter(this::isPassed)
                .filter(score -> score.getCredit() != null)
                .mapToDouble(StudentScore::getCredit).sum();
        long passed = scores.stream().filter(this::isPassed).count();
        long failed = Math.max(0, scores.size() - passed);
        String strongest = scores.stream().filter(score -> score.getTotalScore() != null)
                .max(Comparator.comparingDouble(StudentScore::getTotalScore))
                .map(score -> score.getCourseName() + "（" + format(score.getTotalScore()) + "分）")
                .orElse("暂无可比较的分数记录");
        String attention = scores.stream().filter(score -> !isPassed(score))
                .map(StudentScore::getCourseName).filter(name -> name != null && !name.isBlank())
                .distinct().limit(3).reduce((left, right) -> left + "、" + right).orElse("暂无");
        return String.format(Locale.ROOT,
                "%s共记录%d门课程，其中%d门有数值成绩；已通过%d门，待关注%d门，已取得学分%s。数值成绩平均分%s，最高分课程为%s。待关注课程：%s。以上内容由本地规则统计生成，请以学校审核结果为准。",
                rangeLabel, scores.size(), gradedCount, passed, failed, format(credits),
                format(average), strongest, attention);
    }

    private boolean isPassed(StudentScore score) {
        if (score == null) return false;
        if (score.getPassFlag() != null && !score.getPassFlag().isBlank()) {
            String flag = score.getPassFlag().trim();
            if ("是".equals(flag) || "通过".equals(flag) || "合格".equals(flag)
                    || "true".equalsIgnoreCase(flag) || "1".equals(flag)) return true;
            if ("否".equals(flag) || "不通过".equals(flag) || "不合格".equals(flag)
                    || "false".equalsIgnoreCase(flag) || "0".equals(flag)) return false;
        }
        Double scoreValue = score.getTotalScore() != null ? score.getTotalScore() : score.getFinalScore();
        return scoreValue != null && scoreValue >= 60;
    }

    private String format(double value) {
        if (Math.rint(value) == value) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void saveSummary(Integer studentInfoId, String schoolYear, String term, String summaryText, String summaryType) {
        AiSummary summary = new AiSummary();
        summary.setStudentInfoId(studentInfoId);
        summary.setSchoolYear(schoolYear);
        summary.setTerm(term);
        summary.setSummaryText(summaryText);
        summary.setSummaryType(summaryType);

        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            AiSummaryMapper mapper = sqlSession.getMapper(AiSummaryMapper.class);
            mapper.upsert(summary);
        }
    }
}
