package service;

import dto.ProfileDto;
import mapper.StudentInfoMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pojo.StudentInfo;
import util.SqlSessionFactoryUtils;


/**
 * 用户业务逻辑层
 * 负责：学生信息查找/修改、个人中心补填等
 */
public class StudentInfoService {

    private static final Logger LOGGER = LoggerFactory.getLogger("StudentInfoService");
    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();

    public StudentInfo getStudentInfoById(int accountId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            return studentInfoMapper.selectById(accountId);
        } catch (Exception err) {
            LOGGER.error("修改失败，用户名id：{}", accountId, err);
        }
        return null;
    }

    public boolean modifyInfo(ProfileDto profileDto) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            StudentInfo studentInfo = new StudentInfo();
            studentInfo.setAccountId(profileDto.getId());
            studentInfo.setStuId(profileDto.getStuId());
            studentInfo.setRealName(profileDto.getRealName());
            studentInfo.setGender(profileDto.getGender());
            studentInfo.setBirthday(profileDto.getBirthday());
            studentInfo.setPhone(profileDto.getPhone());
            studentInfo.setMajor(profileDto.getMajor());
            studentInfo.setClassName(profileDto.getClassName());
            studentInfo.setEnrollYear(profileDto.getEnrollYear());
            studentInfo.setSignature(profileDto.getSignature());

            studentInfoMapper.update(studentInfo);
            sqlSession.commit();
            return true;
        } catch (Exception err) {
            LOGGER.error("修改个人资料失败，accountId={}", profileDto == null ? null : profileDto.getId(), err);
            return false;
        }
    }
    public boolean modifyAvatarURL(int accountId, String avatarUrl) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            int rows = studentInfoMapper.updateAvatarURLById(accountId, avatarUrl);
            return rows > 0;
        } catch (Exception err) {
            LOGGER.error("修改avatarUrl失败，用户名id：{}", accountId, err);
        }
        return false;
    }

    public String getAvatarById(int accountId){
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            return studentInfoMapper.selectAvatarById(accountId);
        } catch (Exception err) {
            LOGGER.error("修改失败，用户名id：{}", accountId, err);
        }
        return null;
    }

    public boolean modifyCoverURL(int accountId, String newCoverUrl) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            int row = studentInfoMapper.updateCoverURLById(accountId, newCoverUrl);
            return row != 0;
        } catch (Exception err) {
            LOGGER.error("修改avatarUrl失败，用户名id：{}", accountId, err);
        }
        return false;
    }

    public String getCoverById(int accountId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentInfoMapper studentInfoMapper = sqlSession.getMapper(StudentInfoMapper.class);
            return studentInfoMapper.selectCoverById(accountId);
        } catch (Exception err) {
            LOGGER.error("修改失败，用户名id：{}", accountId, err);
        }
        return null;
    }
}
