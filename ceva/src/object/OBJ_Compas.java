package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Compas extends Entity {

    public OBJ_Compas(GamePanel gp) {

        super(gp);

        type = type_buffs;
        name = "Compas";
        down1 = setup("/res/objects/Compas", gp.tileSize , gp.tileSize);
        description = "[Compas]\nAn ancient tool used \nto teleport!";

    }
}