package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Gem extends Entity {

    GamePanel gp;

    public OBJ_Gem(GamePanel gp) {

        super(gp);

        type = type_consumable;
        name = "Gem";
        down1 = setup("/res/objects/Gem", gp.tileSize, gp.tileSize);
        description = "The Dark Heart of an \nancient castle \n[TO BE CONTINUED].";

        setDialogues();
    }
    
    public void setDialogues() {

        dialogues[0][0] = "You pick up a beautiful Dark Gem.";
        dialogues[0][1] = "You found the Dark Heart , the legendary treasure!";
    }
    public boolean use( Entity entity ) {
        
            gp.gameState = gp.cutsceneState;
            gp.csManager.sceneNum = gp.csManager.ending;
            startDialogue(this, 0);
            return true;
        
    }
}
