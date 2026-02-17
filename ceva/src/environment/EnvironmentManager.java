package environment;

import java.awt.Graphics2D;
import main.GamePanel;

public class EnvironmentManager {

    GamePanel gp;
    public Lightning lightning;

    public int dayState = 0;
    public final int day = 0;
    public final int dusk = 1;
    public final int night = 2;
    public final int dawn = 3;

    public float filterAlpha = 0f; 
    int dayCounter = 0;
    
    public final int dayDuration = 3 * 60 * 60;      // 3 minutes, time * 60 (FPS) = total frames for day/night cycle
    public final int transitionDuration = 2 * 60 * 60; // 2 minute transition (3600 frames at 60 FPS)

    // ADD THIS LINE HERE:
    // This calculates exactly how much to fade per frame
    float transitionSpeed = 0.95f / transitionDuration; 

    public EnvironmentManager(GamePanel gp) {
        this.gp = gp;
    }

    public void setup() {
        lightning = new Lightning(gp);
    }

    public void update() {
        if (dayState == day) {
            dayCounter++;
            if (dayCounter > dayDuration) {
                dayState = dusk;
                dayCounter = 0;
            }
        }
        if (dayState == dusk) {
            // CHANGE THIS LINE: Use the speed variable
            filterAlpha += transitionSpeed; 
            
            if (filterAlpha >= 0.95f) {
                filterAlpha = 0.95f;
                dayState = night;
            }
        }
        if (dayState == night) {
            dayCounter++;
            if (dayCounter > dayDuration) {
                dayState = dawn;
                dayCounter = 0;
            }
        }
        if (dayState == dawn) {
            // CHANGE THIS LINE: Use the speed variable
            filterAlpha -= transitionSpeed; 

            if (filterAlpha <= 0f) {
                filterAlpha = 0f;
                dayState = day;
            }
        }
    }

    public void draw(Graphics2D g2) {
        if (dayState != day || filterAlpha > 0) {
            lightning.draw(g2, filterAlpha);
        }
    }
}
