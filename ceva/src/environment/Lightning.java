package environment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.Shape;

import main.GamePanel;



public class Lightning {

    GamePanel gp;
    BufferedImage darknessFilter;

    public Lightning(GamePanel gp, int circleSize) {

        darknessFilter = new BufferedImage(gp.screenWidth, gp.screenHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = (Graphics2D) darknessFilter.getGraphics();
        Area screenArea = new Area(new Rectangle2D.Double(0,0,gp.screenWidth, gp.screenHeight));


        int centerX = gp.player.screenX + (gp.tileSize / 2);
        int centerY = gp.player.screenY + (gp.tileSize / 2);

        //get the top left x and y of the last circle
        double x = centerX - (circleSize / 2);
        double y = centerY - (circleSize / 2);


        // Create a light circle shape
        Shape circleShape = new Ellipse2D.Double(x, y, circleSize, circleSize);
        Area lightArea = new Area(circleShape);


        // Subtract the light area from the screen area to create darkness
        screenArea.subtract(lightArea);

        // Create gradiation effect within the light circle
        Color color[] = new Color[12];
        float Fraction[] = new float[12];

        color[0] = new Color(0,0,0,0.1f);
        color[1] = new Color(0,0,0,0.42f);
        color[2] = new Color(0,0,0,0.52f);
        color[3] = new Color(0,0,0,0.61f);
        color[4] = new Color(0,0,0,0.69f);
        color[5] = new Color(0,0,0,0.76f);
        color[6] = new Color(0,0,0,0.82f);
        color[7] = new Color(0,0,0,0.86f);
        color[8] = new Color(0,0,0,0.91f);
        color[9] = new Color(0,0,0,0.94f);
        color[10] = new Color(0,0,0,0.96f);
        color[11] = new Color(0,0,0,0.98f);

        Fraction[0] = 0f;
        Fraction[1] = 0.4f;
        Fraction[2] = 0.5f;
        Fraction[3] = 0.6f;
        Fraction[4] = 0.65f;
        Fraction[5] = 0.7f;
        Fraction[6] = 0.75f;
        Fraction[7] = 0.8f;
        Fraction[8] = 0.85f;
        Fraction[9] = 0.9f;
        Fraction[10] = 0.95f;
        Fraction[11] = 1f;

        // Create a gradiation paint setting for the light circle
        RadialGradientPaint gPaint = new RadialGradientPaint(centerX, centerY, circleSize / 2, Fraction, color);
        
        g2.setPaint(gPaint);
        g2.fill(lightArea);

        //g2.setColor(new Color(0,0,0,0.95f)); //black with 95% opacity
        g2.fill(screenArea);
        g2.dispose();
    }

    public void draw(Graphics2D g2) {
        g2.drawImage(darknessFilter, 0, 0, null);
    }

}