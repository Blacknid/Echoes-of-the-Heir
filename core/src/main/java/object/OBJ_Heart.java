package object;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Heart extends Entity {

    GamePanel gp;

    public OBJ_Heart(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = TYPE_PICKUP_ONLY;
        name = "Heart";
        down1 = setup("/res/objects/full_heart", gp.tileSize, gp.tileSize);
        image = setup("/res/objects/full_heart", gp.tileSize , gp.tileSize);
        image1 = setup("/res/objects/empty_heart", gp.tileSize , gp.tileSize);

        // Small pickup hitbox
        solidArea.x      = gp.tileSize * 18 / 64;  // 18 at 64px
        solidArea.y      = gp.tileSize * 20 / 64;  // 20 at 64px
        solidArea.width  = gp.tileSize * 28 / 64;  // 28 at 64px
        solidArea.height = gp.tileSize * 28 / 64;  // 28 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    @Override
    public boolean use(Entity entity) {
        if (entity.life < entity.maxLife) {
            entity.life += 2;
            if (entity.life > entity.maxLife) entity.life = entity.maxLife;
            gp.ui.addMessage("+2 HP!", new gfx.Color(80, 220, 80));
            gp.playSE(SFX.EQUIP);
            return true;
        }
        return false;
    }
}
