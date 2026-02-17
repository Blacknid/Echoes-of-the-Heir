package environment;

import java.awt.Graphics2D;
import main.GamePanel;

public class EnvironmentManager {

    GamePanel gp;
    Lightning lightning;

    public EnvironmentManager(GamePanel gp) {
        this.gp = gp;
    }

    public void setup(){
        // UPDATE 1: Pass 'gp' to the constructor!
        // The Lightning class needs 'gp' to know where the player is.
        lightning = new Lightning(gp); 
    }

    public void draw(Graphics2D g2) {
        // UPDATE 2: Uncomment this line to actually draw the darkness.
        lightning.draw(g2);
    }
}