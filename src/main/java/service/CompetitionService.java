package service;

import lombok.extern.slf4j.Slf4j;
import mapper.CompetitionMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.Competition;
import pojo.StudentScore;
import util.SqlSessionFactoryUtils;
import util.SecurityUtil;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class CompetitionService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();
    private static final StudentScoreService studentScoreService = new StudentScoreService();

    public Map<String, Object> queryPage(String keyword, String compLevel, String compStatus, String year, String month, int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePageNum - 1) * safePageSize;

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            CompetitionMapper mapper = sqlSession.getMapper(CompetitionMapper.class);
            List<Competition> list = mapper.selectPage(keyword, compLevel, compStatus, year, month, offset, safePageSize);
            sanitizeOfficialUrls(list);
            long total = mapper.countPage(keyword, compLevel, compStatus, year, month);
            return Map.of("list", list, "total", total);
        } catch (Exception err) {
            log.error("查询竞赛列表失败", err);
            return Map.of("list", Collections.emptyList(), "total", 0L);
        }
    }

    public List<Competition> recommendForStudent(int studentInfoId) {
        List<StudentScore> scores = studentScoreService.queryAllByStudentInfoId(studentInfoId);
        if (scores == null || scores.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> studentTags = buildStudentTags(scores);
        double avgScore = scores.stream().map(StudentScore::getTotalScore).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        List<Competition> candidates;
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            candidates = sqlSession.getMapper(CompetitionMapper.class).selectRecommendCandidates();
            sanitizeOfficialUrls(candidates);
        } catch (Exception err) {
            log.error("查询推荐竞赛候选失败", err);
            return Collections.emptyList();
        }

        List<ScoredCompetition> matched = new ArrayList<>();
        for (Competition competition : candidates) {
            int score = matchScore(competition, studentTags);
            if (score > 0 || avgScore >= 80) {
                matched.add(new ScoredCompetition(competition, score));
            }
        }

        matched.sort(Comparator.comparingInt(ScoredCompetition::score).reversed()
                .thenComparingInt(item -> levelRank(item.competition().getCompLevel()))
                .thenComparing(item -> item.competition().getRegisterEnd(), Comparator.nullsLast(String::compareTo)
                )
        );

        return matched.stream().limit(8).map(ScoredCompetition::competition).collect(Collectors.toList());
    }

    private Set<String> buildStudentTags(List<StudentScore> scores) {
        Set<String> tags = new LinkedHashSet<>();
        for (StudentScore score : scores) {
            String name = score.getCourseName() == null ? "" : score.getCourseName().toLowerCase(Locale.ROOT);

            if (name.contains("程序")
                    || name.contains("算法")
                    || name.contains("数据结构")
                    || name.contains("java")
                    || name.contains("python")
                    || name.contains("计算机")
                    || name.contains("软件")) {
                tags.add("程序设计");
                tags.add("算法");
                tags.add("计算机");
                tags.add("软件开发");
            }
            if (name.contains("数学")
                    || name.contains("统计")
                    || name.contains("概率")) {
                tags.add("数学");
                tags.add("建模");
                tags.add("统计");
                tags.add("数据分析");
            }
            if (name.contains("英语")
                    || name.contains("外语")) {
                tags.add("英语");
                tags.add("外语");
            }
            if (name.contains("创新")
                    || name.contains("创业")) {
                tags.add("创新创业");
            }
        }
        return tags;
    }

    private int matchScore(Competition competition, Set<String> studentTags) {
        String tags = competition.getTags() == null ? "" : competition.getTags();
        int score = 0;

        for (String tag : studentTags) {
            if (tags.contains(tag)) {
                score += 10;
            }
        }

        if ("国家级".equals(competition.getCompLevel())) {
            score += 2;
        } else if ("省级".equals(competition.getCompLevel())) {
            score += 1;
        }

        return score;
    }

    private int levelRank(String level) {
        if ("国家级".equals(level)) return 1;
        if ("省级".equals(level)) return 2;
        if ("校级".equals(level)) return 3;
        return 4;
    }

    private void sanitizeOfficialUrls(List<Competition> competitions) {
        if (competitions == null) {
            return;
        }
        for (Competition competition : competitions) {
            if (competition != null) {
                competition.setOfficialUrl(SecurityUtil.sanitizeHttpUrl(competition.getOfficialUrl()));
            }
        }
    }

    private record ScoredCompetition(Competition competition, int score) {}
}
