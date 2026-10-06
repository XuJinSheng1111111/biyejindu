package util;

import lombok.Getter;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class SqlSessionFactoryUtils {

    @Getter
    private static final SqlSessionFactory sqlSessionFactory;

    static {
        String resource = "mybatis-config.xml";
        Properties properties = new Properties();
        String dbUrl = requireConfig("db.url");
        String dbUsername = requireConfig("db.username");
        String dbPassword = requireConfig("db.password");
        properties.setProperty("db.url", dbUrl);
        properties.setProperty("db.username", dbUsername);
        properties.setProperty("db.password", dbPassword);
        try (InputStream resourceAsStream = Resources.getResourceAsStream(resource)) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(resourceAsStream, properties);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String requireConfig(String key) {
        String value = AuthConfig.get(key, "");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必要数据库配置：" + key);
        }
        return value;
    }
}
