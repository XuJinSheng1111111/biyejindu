package service;

import lombok.extern.slf4j.Slf4j;
import mapper.AccountMapper;
import mapper.StudentInfoMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.Account;
import util.SqlSessionFactoryUtils;

@Slf4j
public class AccountService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();

    public Account queryByEmail(String email) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            return accountMapper.selectByEmail(email);
        } catch (Exception err) {
            log.error("根据邮箱查询用户失败，email={}", email, err);
            return null;
        }
    }

    /**
     * 保留用户名校验，兼容旧数据和后台兼容逻辑；登录页面不再使用。
     */
    public Account queryByUsername(String username) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            return accountMapper.selectByUsername(username);
        } catch (Exception err) {
            log.error("根据用户名查询用户失败，username={}", username, err);
            return null;
        }
    }

    public boolean add(Account account) {
        if (account == null || account.getEmail() == null) {
            return false;
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            if (accountMapper.insert(account) != 1 || account.getId() == null) {
                sqlSession.rollback();
                return false;
            }
            int profileRows = sqlSession.getMapper(StudentInfoMapper.class)
                    .insertEmpty(account.getId());
            if (profileRows != 1) {
                sqlSession.rollback();
                return false;
            }
            sqlSession.commit();
            return true;
        } catch (Exception err) {
            log.error("添加用户失败，email={}", account.getEmail(), err);
            return false;
        }
    }

    public boolean modifyPwdById(Account account) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            return accountMapper.updatePwdById(account) > 0;
        } catch (Exception err) {
            log.error("修改密码失败，id={}", account == null ? null : account.getId(), err);
            return false;
        }
    }

    public Account getUserById(Integer id) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            return accountMapper.selectById(id);
        }
    }

    public void incrementLoginFailure(Integer accountId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            accountMapper.incrementLoginFailure(accountId);
        }
    }

    public void resetLoginSecurity(Integer accountId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            accountMapper.resetLoginSecurity(accountId);
        }
    }
    public boolean deleteById(Integer accountId) {
        if (accountId == null) {
            return false;
        }
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            AccountMapper accountMapper = sqlSession.getMapper(AccountMapper.class);
            return accountMapper.deleteById(accountId) == 1;
        } catch (Exception err) {
            log.error("注销账号失败，id={}", accountId, err);
            return false;
        }
    }
}