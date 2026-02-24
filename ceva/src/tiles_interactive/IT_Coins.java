package tiles_interactive;

import java.awt.Color;
import entity.Entity;
import main.GamePanel;

public class IT_Coins extends interactiveTile {

    public IT_Coins(GamePanel gp, int col, int row) {
        super(gp, col, row);

        down1 = setup("/res/interactive/Coins", gp.tileSize, gp.tileSize);
        destructible = true;  // Make it destroyable
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        // Player must hold a book to destroy the coin tile
        if (entity.currentWeapon != null && entity.currentWeapon.type == entity.type_book) {
            return true;
        }
        return false;
    }

    public Color getParticleColor() { return new Color(255, 215, 0); }
    public int getParticleSize() { return 10; }
    public int getParticleSpeed() { return 1; }
    public int getParticleMaxLife() { return 20; }
}