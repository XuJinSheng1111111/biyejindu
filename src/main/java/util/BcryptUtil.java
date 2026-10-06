package util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 当前Web系统最安全的密码加密工具 BCrypt
 * 自动随机加盐、可调安全强度、不可逆
 */
public class BcryptUtil {
    // 安全强度 cost 推荐10~12，数字越大越安全、加密越慢
    private static final int COST = 14;

    /**
     * 加密明文密码，返回完整密文（自带随机盐，直接存入数据库）
     * @param rawPwd 前端传来的明文密码
     * @return 加密字符串，数据库password字段长度设60以上
     */
    public static String encrypt(String rawPwd) {
        // 自动生成随机盐，加盐加密
        String salt = BCrypt.gensalt(COST);
        return BCrypt.hashpw(rawPwd, salt);
    }

    /**
     * 校验密码
     * @param rawPwd 用户输入的明文
     * @param dbEncryptPwd 数据库存储的Bcrypt密文
     * @return true密码正确，false错误
     */
    public static boolean checkPwd(String rawPwd, String dbEncryptPwd) {
        // 自动从密文中提取内置盐，重新加密比对，无需手动管理盐
        return BCrypt.checkpw(rawPwd, dbEncryptPwd);
    }
}