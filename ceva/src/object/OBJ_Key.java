package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Key extends Entity{

    GamePanel gp;

    public OBJ_Key(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_consumable;
        name = "Key";
        down1 = setup("/res/objects/Key", gp.tileSize, gp.tileSize);
        description = "[" + name + "]\nA key used to open \nmysterious doors.";

        setDialogue();

    }

    public void setDialogue() {

        dialogues[0][0] = "You use the " + name + " to open the door.";
        dialogues[1][0] = "There is nothing to use the key on.";

    }

    public boolean use(Entity entity) {

        int objIndex = getDetected(entity, gp.obj, "Door");

        if ( objIndex != 999 ) {

            startDialogue(this, 0);
            gp.playSE(3);
            gp.obj[objIndex] = null;
            return true;
        }
        else {
            startDialogue(this, 1);
            return false;
        }
    }
}
