package service;

import lombok.extern.slf4j.Slf4j;
import mapper.AiSummaryMapper;
import mapper.FileHistoryMapper;
import mapper.StudentScoreMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.AiSummary;
import util.SqlSessionFactoryUtils;

import java.util.Collections;
import java.util.List;

/**
 * 学业数据备份、清理相关业务。
 */
@Slf4j
public class StudentDataService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();

    public List<AiSummary> listSummaries(int studentInfoId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            return sqlSession.getMapper(AiSummaryMapper.class).selectAllByStudentInfoId(studentInfoId);
        } catch (Exception err) {
            log.error("查询学业总结失败，studentInfoId={}", studentInfoId, err);
            return Collections.emptyList();
        }
    }

    /**
     * 清空成绩、学业总结和上传历史，保留账号与个人资料。
     */
    public boolean clearAcademicData(int studentInfoId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            try {
                sqlSession.getMapper(StudentScoreMapper.class).deleteByStudentInfoId(studentInfoId);
                sqlSession.getMapper(AiSummaryMapper.class).deleteByStudentInfoId(studentInfoId);
                sqlSession.getMapper(FileHistoryMapper.class).deleteByStudentInfoId(studentInfoId);
                sqlSession.commit();
                return true;
            } catch (Exception err) {
                sqlSession.rollback();
                throw err;
            }
        } catch (Exception err) {
            log.error("清空学业数据失败，studentInfoId={}", studentInfoId, err);
            return false;
        }
    }
}
