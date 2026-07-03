package tile;

import gfx.Color;
import main.GamePanel;

public class IT_Coins extends interactiveTile {

    public int coinValue = 1;

    public IT_Coins(GamePanel gp, int col, int row) {
        super(gp, col, row);

        down1 = setup("/res/interactive/Coins", gp.tileSize, gp.tileSize);
    }

    public Color getParticleColor() { return new Color(255, 215, 0); }
    public int getParticleSize() { return 10; }
    public int getParticleSpeed() { return 1; }
    public int getParticleMaxLife() { return 20; }
}