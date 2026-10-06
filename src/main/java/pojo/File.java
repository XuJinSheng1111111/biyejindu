package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class File {
    private Integer id;
    private String fileName;
    private Integer courseSum;
    private String uploadTime;
    private Integer state;
    private Integer studentInfoId;
}
