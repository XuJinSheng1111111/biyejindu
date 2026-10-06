package pojo;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
//@JSONType(naming = PropertyNamingStrategy.CamelCase)
public class StudentScore {
    private Integer id;
    private String schoolYear;
    private String term;
    private String courseNo;
    private String courseName;
    private String serialNo;
    private String courseGroup;
    private Double finalScore;
    private Double totalScore;
    private Double gpa;
    private Double credit;
    private String remark;
    private String examType;
    private String courseType;
    private String passFlag;
    private Integer studentInfoId;

    public StudentScore(String schoolYear, String term, String courseNo, String courseName, String serialNo, String courseGroup, Double finalScore, Double totalScore, Double gpa, Double credit, String remark, String examType, String courseType, String passFlag) {
        this.schoolYear = schoolYear;
        this.term = term;
        this.courseNo = courseNo;
        this.courseName = courseName;
        this.serialNo = serialNo;
        this.courseGroup = courseGroup;
        this.finalScore = finalScore;
        this.totalScore = totalScore;
        this.gpa = gpa;
        this.credit = credit;
        this.remark = remark;
        this.examType = examType;
        this.courseType = courseType;
        this.passFlag = passFlag;
    }
    public StudentScore(StudentScore studentScore) {
        schoolYear = studentScore.schoolYear;
        term = studentScore.term;
        courseNo = studentScore.courseNo;
        courseName = studentScore.courseName;
        serialNo = studentScore.serialNo;
        courseGroup = studentScore.courseGroup;
        finalScore = studentScore.finalScore;
        totalScore = studentScore.totalScore;
        gpa =studentScore.gpa;
        credit = studentScore.credit;
        remark = studentScore.remark;
        examType = studentScore.examType;
        courseType = studentScore.courseType;
        passFlag = studentScore.passFlag;
    }
}
