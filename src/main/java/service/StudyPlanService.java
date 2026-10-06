package service;

import lombok.extern.slf4j.Slf4j;
import mapper.CompetitionMapper;
import mapper.StudyPlanCompetitionMapper;
import mapper.StudyPlanTaskMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.Competition;
import pojo.StudyPlanTask;
import util.SqlSessionFactoryUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
public class StudyPlanService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();
    private static final Set<String> CATEGORIES = Set.of("today", "week", "month");
    private static final Set<String> PRIORITIES = Set.of("high", "mid", "low");

    public List<StudyPlanTask> listTasks(Integer accountId) {
        if (accountId == null) {
            return Collections.emptyList();
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            return sqlSession.getMapper(StudyPlanTaskMapper.class)
                    .selectByAccountId(accountId);
        } catch (Exception err) {
            log.error("查询学习计划失败，accountId={}", accountId, err);
            return Collections.emptyList();
        }
    }

    public StudyPlanTask createTask(Integer accountId, StudyPlanTask input) {
        if (accountId == null || !normalize(input)) return null;

        StudyPlanTask task = new StudyPlanTask();
        task.setAccountId(accountId);
        task.setTitle(input.getTitle().trim());
        task.setContent(blankToNull(input.getContent()));
        task.setCategory(input.getCategory());
        task.setPriority(input.getPriority());
        task.setDeadline(blankToNull(input.getDeadline()));
        task.setDone(false);
        task.setSourceType("manual");
        task.setSourceId(null);

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudyPlanTaskMapper mapper = sqlSession.getMapper(StudyPlanTaskMapper.class);
            if (mapper.insert(task) != 1) {
                sqlSession.rollback();
                return null;
            }
            sqlSession.commit();
            return task;
        } catch (Exception err) {
            log.error("新增学习计划失败，accountId={}", accountId, err);
            return null;
        }
    }

    public boolean updateTask(Integer accountId, StudyPlanTask input) {
        if (accountId == null || input == null || input.getId() == null || !normalize(input)) {
            return false;
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudyPlanTaskMapper mapper = sqlSession.getMapper(StudyPlanTaskMapper.class);
            StudyPlanTask current = mapper.selectByIdAndAccount(input.getId(), accountId);
            if (current == null || !"manual".equals(current.getSourceType())) {
                return false;
            }
            current.setTitle(input.getTitle().trim());
            current.setContent(blankToNull(input.getContent()));
            current.setCategory(input.getCategory());
            current.setPriority(input.getPriority());
            current.setDeadline(blankToNull(input.getDeadline()));

            int rows = mapper.update(current);
            if (rows == 1) {
                sqlSession.commit();
                return true;
            }
            sqlSession.rollback();
            return false;
        } catch (Exception err) {
            log.error("更新学习计划失败，accountId={}, id={}", accountId, input == null ? null : input.getId(), err);
            return false;
        }
    }

    public boolean toggleDone(Integer accountId, Long taskId, Boolean done) {
        if (accountId == null || taskId == null || done == null) {
            return false;
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            return sqlSession.getMapper(StudyPlanTaskMapper.class).updateDone(taskId, accountId, done) == 1;
        } catch (Exception err) {
            log.error("更新学习计划状态失败，accountId={}, id={}", accountId, taskId, err);
            return false;
        }
    }

    public boolean deleteTask(Integer accountId, Long taskId) {
        if (accountId == null || taskId == null) {
            return false;
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudyPlanTaskMapper taskMapper = sqlSession.getMapper(StudyPlanTaskMapper.class);
            StudyPlanTask task = taskMapper.selectByIdAndAccount(taskId, accountId);
            if (task == null) return false;

            if (taskMapper.delete(taskId, accountId) != 1) {
                sqlSession.rollback();
                return false;
            }
            if ("competition".equals(task.getSourceType()) && task.getSourceId() != null) {
                sqlSession.getMapper(StudyPlanCompetitionMapper.class)
                        .delete(accountId, task.getSourceId());
            }
            sqlSession.commit();
            return true;
        } catch (Exception err) {
            log.error("删除学习计划失败，accountId={}, id={}", accountId, taskId, err);
            return false;
        }
    }

    public boolean addCompetitionToPlan(Integer accountId, Long competitionId) {
        if (accountId == null || competitionId == null) {
            return false;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudyPlanTaskMapper taskMapper = sqlSession.getMapper(StudyPlanTaskMapper.class);
            StudyPlanTask existing = taskMapper.selectBySource(accountId, "competition", competitionId);
            if (existing != null) {
                sqlSession.getMapper(StudyPlanCompetitionMapper.class).insertIgnore(accountId, competitionId);
                sqlSession.commit();
                return true;
            }
            Competition competition = sqlSession.getMapper(CompetitionMapper.class).selectById(competitionId);
            if (competition == null) {
                return false;
            }

            StudyPlanTask task = buildCompetitionTask(accountId, competition);
            if (taskMapper.insert(task) != 1) {
                sqlSession.rollback();
                return false;
            }
            sqlSession.getMapper(StudyPlanCompetitionMapper.class).insertIgnore(accountId, competitionId);sqlSession.commit();
            return true;
        } catch (Exception err) {
            log.error("添加竞赛到学习计划失败，accountId={}, competitionId={}", accountId, competitionId, err);
            return false;
        }
    }

    private StudyPlanTask buildCompetitionTask(Integer accountId, Competition competition) {
        String deadline = normalizeDate(competition.getRegisterEnd());
        LocalDate date = parseDate(deadline);
        long days = date == null ? 365 : ChronoUnit.DAYS.between(LocalDate.now(), date);

        StudyPlanTask task = new StudyPlanTask();
        task.setAccountId(accountId);
        task.setTitle(limit(competition.getCompName(), 40));
        task.setContent(limit("报名截止：" + displayDate(deadline)
                + " · " + safe(competition.getOrganizer()), 200));
        task.setCategory(days <= 7 ? "today" : (days <= 30 ? "week" : "month"));
        task.setPriority(days <= 7 ? "high" : (days <= 30 ? "mid" : "low"));
        task.setDeadline(deadline);
        task.setDone(false);
        task.setSourceType("competition");
        task.setSourceId(competition.getId());
        return task;
    }

    private boolean normalize(StudyPlanTask task) {
        if (task == null || task.getTitle() == null || task.getTitle().isBlank()) {
            return false;
        }
        String title = task.getTitle().trim();
        if (title.length() > 40) return false;
        if (task.getContent() != null && task.getContent().length() > 200) {
            return false;
        }
        if (!CATEGORIES.contains(task.getCategory())) return false;
        if (!PRIORITIES.contains(task.getPriority())) return false;

        return task.getDeadline() == null
                || task.getDeadline().isBlank()
                || parseDate(task.getDeadline()) != null;
    }

    private String normalizeDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.replace('T', ' ').trim();
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException err) {
            return null;
        }
    }

    private String displayDate(String value) {
        return value == null || value.isBlank() ? "以官网通知为准" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String limit(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}