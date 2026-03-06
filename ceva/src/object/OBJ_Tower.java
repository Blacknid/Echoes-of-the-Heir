package object;
import entity.Entity;
import main.GamePanel;

public class OBJ_Tower extends Entity {
    public OBJ_Tower(GamePanel gp) {
        super(gp);

        type = type_obstacle;
        name = "Tower";
        description = "A tall, imposing tower.\nBlocks the way.\nCame from under the eye";
        collision = true; // Player cannot walk through it

        // Load the tower sprite (assuming it's a single image)
        down1 = setup("/res/objects/tower", gp.tileSize, gp.tileSize); // 32x64 texture
        
    }
}