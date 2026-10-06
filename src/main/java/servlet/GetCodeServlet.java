package servlet;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Random;

/**
 * 图形验证码
 * */
@WebServlet("/getCode")
public class GetCodeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        resp.setContentType("image/png");

        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setDateHeader("Expires", 0);


        int width = 120;
        int height = 48;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics g = image.getGraphics();

        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0,0,width,height);


        Random random = new Random();
        String codeSource = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder realCode = new StringBuilder();
        for(int i=0;i<4;i++){
            int index = random.nextInt(codeSource.length());
            realCode.append(codeSource.charAt(index));
        }
        String codeStr = realCode.toString();


        HttpSession session = req.getSession();
        session.setAttribute("verifyCode", codeStr);


        g.setFont(new Font("黑体", Font.BOLD, 30));
        for(int i=0;i<4;i++){
            g.setColor(new Color(random.nextInt(255),random.nextInt(255),random.nextInt(255)));
            g.drawString(String.valueOf(codeStr.charAt(i)),10+i*26,32);
        }

        for(int i=0;i<8;i++){
            g.setColor(new Color(random.nextInt(255),random.nextInt(255),random.nextInt(255)));
            int x1 = random.nextInt(width);
            int y1 = random.nextInt(height);
            int x2 = random.nextInt(width);
            int y2 = random.nextInt(height);
            g.drawLine(x1,y1,x2,y2);
        }

        g.dispose();
        ImageIO.write(image, "png", resp.getOutputStream());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
}