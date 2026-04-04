package object;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Gem extends Entity {

    GamePanel gp;
    public static final String objName = "Gem";

    public OBJ_Gem(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = TYPE_PICKUP_ONLY;
        name = objName;
        down1 = setup("/res/objects/Gem", gp.tileSize, gp.tileSize);
        description = "Dark Heart of an\nancient castle.";
        
        // HITBOX: Medium gem hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogues();
    }
    
    public void setDialogues() {

        ensureDialogues()[0][0] = "You pick up a beautiful Dark Gem.";
        ensureDialogues()[0][1] = "You found the Dark Heart , the legendary treasure!";
        ensureDialogues()[0][2] = "As you hold it, a sudden warmth \nfills your body.";
        ensureDialogues()[1][0] = "You feel a strange aura \nemanating from the gem.\n It seems you are not\nworthy to possess it yet.";
    }
    public boolean use( Entity entity ) {

        // Prevent repeated gem pickup logic if player already owns it.
        if (gp.player.hasGem > 0) {
            return true;
        }
        
        if ( gp.player.level >= 3 ) {
            gp.player.hasGem = 1;
            gp.playSE(SFX.EQUIP);
            startDialogue(this, 0);
            gp.gameState = GamePanel.cutsceneState;
            gp.csManager.sceneNum = gp.csManager.ending;
            gp.csManager.scenePhase = 0;
            return true;
        }
        else {
            startDialogue(this, 1);
            return false;
        }
    }
}
