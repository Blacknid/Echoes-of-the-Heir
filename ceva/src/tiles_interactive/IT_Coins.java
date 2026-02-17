package tiles_interactive;

import java.awt.Color;
import entity.Entity;
import main.GamePanel;

public class IT_Coins extends interactiveTile {

    GamePanel gp;

    public IT_Coins(GamePanel gp, int col, int row) {

        super(gp, col, row);
        this.gp = gp;

        this.worldX = gp.tileSize * col;
        this.worldY = gp.tileSize * row;

        down1 = setup("/res/interactive/Coins", gp.tileSize, gp.tileSize);
        destructible = true;
    }

    public boolean isCorrectItem(Entity entity) {

        boolean isCorrectItem = false;
        
        if ( entity.currentWeapon.type == type_book) {

            isCorrectItem = true;

        }

        return isCorrectItem;

    }

    public Color getParticleColor() {
        Color color = new Color(255, 215, 0);
        return color;
    }
    public int getParticleSize() {
        int size = 10; // pixels
        return size;
    }
    public int getParticleSpeed() {
        int speed = 1;
        return speed;
    }
    public int getParticleMaxLife() {
        int maxLife = 20;
        return maxLife;
    }

}
