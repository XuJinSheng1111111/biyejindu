package service;

import lombok.extern.slf4j.Slf4j;
import mapper.FileHistoryMapper;
import mapper.StudentScoreMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.jspecify.annotations.NonNull;
import pojo.File;
import pojo.StudentScore;
import util.SqlSessionFactoryUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * @author ondog
 * @version 1.0
 * @since 2026-07-21
 * 学生成绩业务层
 *
 */
@Slf4j
public class StudentScoreService {

    private final DataFormatter dataFormatter = new DataFormatter();
    private static final DateTimeFormatter UPLOAD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_IMPORT_ROWS = 20000;
    private static final long MAX_ZIP_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long MAX_EXTRACTED_TEXT_BYTES = 5L * 1024 * 1024;

    static {
        ZipSecureFile.setMinInflateRatio(0.02);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_EXTRACTED_TEXT_BYTES);
    }

    private final static SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();

    /**
     * @param schoolYear    学年
     * @param term          学期
     * @param courseName    课程名
     * @param studentInfoId 学生信息表主键id
     * @return 返回List集合
     *
     */
    public List<StudentScore> queryByCondition(String schoolYear, String term, String courseName, int studentInfoId) {
        StudentScoreMapper sqlSessionMapper;
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            sqlSessionMapper = sqlSession.getMapper(StudentScoreMapper.class);
            String startYear = null;
            String endYear = null;
            if (schoolYear != null && !schoolYear.isEmpty()) {
                String[] years = schoolYear.split("-");
                startYear = String.valueOf(Integer.parseInt(years[0]));
                endYear = String.valueOf(Integer.parseInt(years[1]));
            }
            return sqlSessionMapper.selectByCondition(startYear, endYear, term, courseName, studentInfoId);
        } catch (Exception err) {
            log.error("查询全部成绩失败：", err);
            return null;
        }

    }

    public List<StudentScore> parseExcel(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("文件中没有可读取的工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) {
                throw new IOException("成绩单行数超过限制");
            }
            ArrayList<StudentScore> studentScoresList = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                StudentScore studentScore = getScore(row);
                if (studentScore.getCourseName().trim().isEmpty() && studentScore.getCourseNo().trim().isEmpty()) {
                    continue;
                }
                studentScoresList.add(studentScore);
            }
            return studentScoresList;
        }

    }


    public List<StudentScore> queryAllByStudentInfoId(int studentInfoId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentScoreMapper mapper = sqlSession.getMapper(StudentScoreMapper.class);
            return mapper.selectAllByStudentInfoId(studentInfoId);
        } catch (Exception err) {
            log.error("查询全部学期成绩失败，studentInfoId={}", studentInfoId, err);
            return new ArrayList<>();
        }
    }

    public List<StudentScore> queryTermListByStudentInfoId(int studentInfoId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentScoreMapper mapper = sqlSession.getMapper(StudentScoreMapper.class);
            return mapper.selectTermListByStudentInfoId(studentInfoId);
        } catch (Exception err) {
            log.error("查询学期列表失败，studentInfoId={}", studentInfoId, err);
            return new ArrayList<>();
        }
    }

    public List<StudentScore> queryByStudentAndTerm(
            String schoolYear,
            String term,
            int studentInfoId
    ) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentScoreMapper mapper = sqlSession.getMapper(StudentScoreMapper.class);
            return mapper.selectByStudentAndTerm(schoolYear, term, studentInfoId);
        } catch (Exception err) {
            log.error("查询学期成绩失败，studentInfoId={}, schoolYear={}, term={}",
                    studentInfoId, schoolYear, term, err);
            return new ArrayList<>();
        }
    }

    /**
     * 确认导入成绩时使用显式事务。
     * 只有批量写入成功后才提交，异常时统一回滚。
     */
    public int saveScoreList(List<StudentScore> studentScores) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            StudentScoreMapper studentScoreMapper = sqlSession.getMapper(StudentScoreMapper.class);
            Iterator<StudentScore> iterator = studentScores.iterator();
            while (iterator.hasNext()) {
                StudentScore studentScore = iterator.next();
                String courseNo = studentScore.getCourseNo();
                Integer studentInfoId = studentScore.getStudentInfoId();
                StudentScore state = studentScoreMapper.selectBycourseNo(courseNo, studentInfoId);
                if (state != null) {
                    iterator.remove();
                }
            }
            if (studentScores.isEmpty()) {
                sqlSession.rollback();
                return -1;
            }
            int rows = studentScoreMapper.batchInsert(studentScores);
            if (rows > 0) {
                sqlSession.commit();
                return rows;
            }
            sqlSession.rollback();
            return -1;
        } catch (Exception err) {
            log.error("批量导入成绩失败", err);
            return -1;
        }
    }

    public List<File> queryFileHistoryByStudentInfoId(int studentInfoId) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            FileHistoryMapper fileHistoryMapper = sqlSession.getMapper(FileHistoryMapper.class);
            return fileHistoryMapper.selectByStudentInfoId(studentInfoId);
        } catch (Exception err) {
            log.error("查询上传历史失败，studentInfoId={}", studentInfoId, err);
            return new ArrayList<>();
        }
    }

    public int saveFileHistory(String fileName, int size, int state, Integer studentInfoId) {
        File file = new File();
        file.setFileName(fileName);
        file.setCourseSum(size);
        file.setUploadTime(LocalDateTime.now().format(UPLOAD_TIME_FORMATTER));
        file.setState(state);
        file.setStudentInfoId(studentInfoId);
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            FileHistoryMapper fileHistoryMapper = sqlSession.getMapper(FileHistoryMapper.class);
            return fileHistoryMapper.insert(file);
        }
    }

    private @NonNull StudentScore getScore(Row row) throws IOException {
        String schoolYear = getCellValue(row.getCell(0));
        String term = getCellValue(row.getCell(1));
        String courseNo = getCellValue(row.getCell(2));
        String courseName = getCellValue(row.getCell(3));
        String serialNo = getCellValue(row.getCell(4));
        String courseGroup = getCellValue(row.getCell(5));
        double finalScore = safeDoubleValue(getCellValue(row.getCell(6)));
        double totalScore = safeDoubleValue(getCellValue(row.getCell(7)));
        double gpa = safeDoubleValue(getCellValue(row.getCell(8)));
        double credit = safeDoubleValue(getCellValue(row.getCell(9)));
        String remark = getCellValue(row.getCell(10));
        String examType = getCellValue(row.getCell(11));
        String courseType = getCellValue(row.getCell(12));
        String passFlag = getCellValue(row.getCell(13));
        return new StudentScore(schoolYear, term, courseNo, courseName, serialNo, courseGroup, finalScore, totalScore, gpa, credit, remark, examType, courseType, passFlag);
    }

    private Double safeDoubleValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int safeIntValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getCellValue(Cell cell) throws IOException {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new IOException("成绩文件不能包含公式单元格，请先转为固定值");
        }
        return dataFormatter.formatCellValue(cell);
    }

}
