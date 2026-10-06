package service;

import lombok.extern.slf4j.Slf4j;
import mapper.UserSettingsMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.UserSettings;
import util.SqlSessionFactoryUtils;

import java.util.Locale;
import java.util.Set;

@Slf4j
public class UserSettingsService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();

    private static final Set<String> THEMES = Set.of("light", "dark", "auto");
    private static final Set<String> THEME_COLORS = Set.of("blue", "green", "orange", "red", "purple");
    private static final Set<String> FONT_SIZES = Set.of("normal", "large", "xlarge");

    public UserSettings getOrCreate(Integer accountId) {
        requireAccountId(accountId);

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UserSettingsMapper mapper = sqlSession.getMapper(UserSettingsMapper.class);
            UserSettings settings = mapper.selectByAccountId(accountId);
            if (settings != null) {
                return settings;
            }

            UserSettings defaults = defaultSettings(accountId);
            mapper.insertIfAbsent(defaults);
            sqlSession.commit();
            return mapper.selectByAccountId(accountId);
        } catch (Exception err) {
            log.error("读取用户显示设置失败，accountId={}", accountId, err);
            throw new RuntimeException("读取用户显示设置失败", err);
        }
    }

    public UserSettings save(Integer accountId, UserSettings input) {
        requireAccountId(accountId);
        if (input == null) {
            throw new IllegalArgumentException("设置内容不能为空");
        }

        UserSettings current = getOrCreate(accountId);
        UserSettings next = new UserSettings();
        next.setId(current.getId());
        next.setAccountId(accountId);
        next.setTheme(resolveValue(input.getTheme(), current.getTheme(), THEMES, "主题"));
        next.setThemeColor(resolveValue(input.getThemeColor(), current.getThemeColor(), THEME_COLORS, "主题色"));
        next.setFontSize(resolveValue(input.getFontSize(), current.getFontSize(), FONT_SIZES, "字号"));
        next.setSidebarCollapsed(input.getSidebarCollapsed() == null ? Boolean.TRUE.equals(current.getSidebarCollapsed()) : input.getSidebarCollapsed());

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UserSettingsMapper mapper = sqlSession.getMapper(UserSettingsMapper.class);
            mapper.update(next);
            sqlSession.commit();
            return mapper.selectByAccountId(accountId);
        } catch (Exception err) {
            log.error("保存用户显示设置失败，accountId={}", accountId, err);
            throw new RuntimeException("保存用户显示设置失败", err);
        }
    }

    public boolean markGuideCompleted(Integer accountId) {
        requireAccountId(accountId);
        getOrCreate(accountId);

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            UserSettingsMapper mapper = sqlSession.getMapper(UserSettingsMapper.class);
            int rows = mapper.updateGuideCompleted(accountId, true);
            sqlSession.commit();
            return rows > 0;
        } catch (Exception err) {
            log.error("更新新手指引状态失败，accountId={}", accountId, err);
            throw new RuntimeException("更新新手指引状态失败", err);
        }
    }
    private UserSettings defaultSettings(Integer accountId) {
        UserSettings settings = new UserSettings();
        settings.setAccountId(accountId);
        settings.setTheme("light");
        settings.setThemeColor("blue");
        settings.setFontSize("normal");
        settings.setSidebarCollapsed(false);
        settings.setGuideCompleted(false);
        return settings;
    }

    private String resolveValue(String value, String current, Set<String> allowedValues, String fieldName) {
        if (value == null || value.isBlank()) {
            return allowedValues.contains(current) ? current : allowedValues.iterator().next();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowedValues.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + "取值不合法");
        }
        return normalized;
    }

    private void requireAccountId(Integer accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("账号信息不能为空");
        }
    }
}