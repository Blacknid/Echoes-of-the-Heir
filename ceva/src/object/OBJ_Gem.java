package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Gem extends Entity {

    GamePanel gp;
    public static final String objName = "Gem";

    public OBJ_Gem(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_pickupOnly;
        name = objName;
        down1 = setup("/res/objects/Gem", gp.tileSize, gp.tileSize);
        description = "The Dark Heart of an \nancient castle \n[TO BE CONTINUED].";

        setDialogues();
    }
    
    public void setDialogues() {

        dialogues[0][0] = "You pick up a beautiful Dark Gem.";
        dialogues[0][1] = "You found the Dark Heart , the legendary treasure!";
        dialogues[0][2] = "As you hold it, a sudden warmth \nfills your body.";
        dialogues[1][0] = "You feel a strange aura \nemanating from the gem.\n It seems you are not\nworthy to possess it yet.";
    }
    public boolean use( Entity entity ) {
        
        if ( gp.player.level >= 3 ) {
            gp.playSE(2);
            startDialogue(this, 0);
            gp.gameState = gp.cutsceneState;
            gp.csManager.sceneNum = gp.csManager.ending;
            return true;
        }
        else {
            startDialogue(this, 1);
            return false;
        }
    }
}
