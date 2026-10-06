package pojo;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String msg;
    private T  data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMsg("请求成功！");
        r.setData(data);
        return r;
    }
    public static <T> Result<T> fail(Integer code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(null);
        return r;
    }
}
