package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import main.GamePanel;
import main.KeyHandler;
import object.OBJ_Chest1;
import object.OBJ_Compas;
import object.OBJ_Shield_Wood;
import object.OBJ_Sword_Normal;

public class Player extends Entity{

    KeyHandler keyH;

    public final int screenX;
    public final int screenY;
    public boolean attackCanceled = false;
    public ArrayList<Entity> inventory = new ArrayList<>();
    public final int maxInventorySize = 20;

    public int hasKey = 0;
    public int hasArtefact = 0;
    public int hasGem = 0;
    int counter = 0;

    public Player(GamePanel gp, KeyHandler keyH) {

        super(gp);

        this.keyH = keyH;

        screenX = gp.screenWidth / 2 - (gp.tileSize / 2);
        screenY = gp.screenHeight / 2 - (gp.tileSize / 2);

        solidArea = new Rectangle();
        solidArea.x = (int)20;
        solidArea.y = 25;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        solidArea.height = 20;
        solidArea.width = 20;

        //ATTACK AREA
        attackArea.width = 36;
        attackArea.height = 36;

        setDefaultValues();
        getPlayerImage();
        getPlayerAttackImage();
        setItems();
        setDialogue();
    }
    public void setItems() {
        
    }
    public void restoreLifeAndMana() {

        life = maxLife;

    }
    public void setDefaultPositions() {

        worldX = (int)(gp.tileSize * 24.5);
        worldY = (int)(gp.tileSize * 15.5);
        direction = "down";
    }
    public void setDialogue() {

    }
    public void setDefaultValues() {

        worldX = (int)(gp.tileSize * 71.5);
        worldY = (int)(gp.tileSize * 21.5);
        speed = (int) 4.5;
        direction = "down";

        // PLAYER STATUS
        level = 1;
        maxLife = 3;
        life = maxLife;
        strenght = 1;
        dexterity = 1;
        exp = 0;
        nextLevelExp = 5;
        coin = 0;
        currentWeapon = new OBJ_Sword_Normal(gp);
        currentShield = new OBJ_Shield_Wood(gp);
        attack = getAttack();
        defense = getDefense();
    }

    public int getAttack() {
        return attack = strenght * currentWeapon.attackValue;
    }

    public int getDefense() {
        return defense = dexterity * currentShield.defenseValue;
    }

    public void getPlayerImage() {

        up1 = setup("/res/player/b.up/b1", gp.tileSize, gp.tileSize);
        up2 = setup("/res/player/b.up/b2", gp.tileSize, gp.tileSize);
        up3 = setup("/res/player/b.up/b3", gp.tileSize, gp.tileSize);
        up4 = setup("/res/player/b.up/b4", gp.tileSize, gp.tileSize);
        up5 = setup("/res/player/b.up/b5", gp.tileSize, gp.tileSize);
        up6 = setup("/res/player/b.up/b6", gp.tileSize, gp.tileSize);
        up7 = setup("/res/player/b.up/b7", gp.tileSize, gp.tileSize);
        down1 = setup("/res/player/b.front/f1", gp.tileSize, gp.tileSize);
        down2 = setup("/res/player/b.front/f2", gp.tileSize, gp.tileSize);
        down3 = setup("/res/player/b.front/f3", gp.tileSize, gp.tileSize);
        down4 = setup("/res/player/b.front/f4", gp.tileSize, gp.tileSize);
        down5 = setup("/res/player/b.front/f5", gp.tileSize, gp.tileSize);
        down6 = setup("/res/player/b.front/f6", gp.tileSize, gp.tileSize);
        down7 = setup("/res/player/b.front/f7", gp.tileSize, gp.tileSize);
        left1 = setup("/res/player/b.left/l1", gp.tileSize, gp.tileSize);
        left2 = setup("/res/player/b.left/l2", gp.tileSize, gp.tileSize);
        left3 = setup("/res/player/b.left/l3", gp.tileSize, gp.tileSize);
        left4 = setup("/res/player/b.left/l4", gp.tileSize, gp.tileSize);
        left5 = setup("/res/player/b.left/l5", gp.tileSize, gp.tileSize);
        left6 = setup("/res/player/b.left/l6", gp.tileSize, gp.tileSize);
        left7 = setup("/res/player/b.left/l7", gp.tileSize, gp.tileSize);
        left8 = setup("/res/player/b.left/l8", gp.tileSize, gp.tileSize);
        right1 = setup("/res/player/b.right/r1", gp.tileSize, gp.tileSize);
        right2 = setup("/res/player/b.right/r2", gp.tileSize, gp.tileSize);
        right3 = setup("/res/player/b.right/r3", gp.tileSize, gp.tileSize);
        right4 = setup("/res/player/b.right/r4", gp.tileSize, gp.tileSize);
        right5 = setup("/res/player/b.right/r5", gp.tileSize, gp.tileSize);
        right6 = setup("/res/player/b.right/r6", gp.tileSize, gp.tileSize);
        right7 = setup("/res/player/b.right/r7", gp.tileSize, gp.tileSize);
        right8 = setup("/res/player/b.right/r8", gp.tileSize, gp.tileSize);
        chest_2 = setup("/res/objects/Chest_opened", gp.tileSize, gp.tileSize);

    }

    public void getPlayerAttackImage() {

        attackUp1 = setup("/res/player/b.attack/up/u1", gp.tileSize, gp.tileSize * 2);
        attackUp2 = setup("/res/player/b.attack/up/u2", gp.tileSize, gp.tileSize * 2);
        attackUp3 = setup("/res/player/b.attack/up/u3", gp.tileSize, gp.tileSize * 2);
        attackUp4 = setup("/res/player/b.attack/up/u4", gp.tileSize, gp.tileSize * 2);
        attackUp5 = setup("/res/player/b.attack/up/u5", gp.tileSize, gp.tileSize * 2);

        attackDown1 = setup("/res/player/b.attack/front/f1", gp.tileSize, gp.tileSize * 2);
        attackDown2 = setup("/res/player/b.attack/front/f2", gp.tileSize, gp.tileSize * 2);
        attackDown3 = setup("/res/player/b.attack/front/f3", gp.tileSize, gp.tileSize * 2);
        attackDown4 = setup("/res/player/b.attack/front/f4", gp.tileSize, gp.tileSize * 2);
        attackDown5 = setup("/res/player/b.attack/front/f5", gp.tileSize, gp.tileSize * 2);

        attackLeft1 = setup("/res/player/b.attack/left/l1", gp.tileSize * 2, gp.tileSize);
        attackLeft2 = setup("/res/player/b.attack/left/l2", gp.tileSize * 2, gp.tileSize);
        attackLeft3 = setup("/res/player/b.attack/left/l3", gp.tileSize * 2, gp.tileSize);
        attackLeft4 = setup("/res/player/b.attack/left/l4", gp.tileSize * 2, gp.tileSize);
        attackLeft5 = setup("/res/player/b.attack/left/l5", gp.tileSize * 2, gp.tileSize);

        attackRight1 = setup("/res/player/b.attack/right/r1", gp.tileSize * 2, gp.tileSize);
        attackRight2 = setup("/res/player/b.attack/right/r2", gp.tileSize * 2, gp.tileSize);
        attackRight3 = setup("/res/player/b.attack/right/r3", gp.tileSize * 2, gp.tileSize);
        attackRight4 = setup("/res/player/b.attack/right/r4", gp.tileSize * 2, gp.tileSize);
        attackRight5 = setup("/res/player/b.attack/right/r5", gp.tileSize * 2, gp.tileSize);
    }

    public void update() {

        if ( attacking == true ) {
            attacking();
        }

        else if (keyH.upPressed == true || keyH.downPressed == true || 
                keyH.leftPressed == true || keyH.rightPressed == true || keyH.enterPressed == true) {

            if(keyH.upPressed == true) {
                direction = "up";
            }
            else if(keyH.downPressed == true) {
                direction = "down";
            }
            else if(keyH.leftPressed == true) {
                direction = "left";
            }
            else if(keyH.rightPressed == true) {
                direction = "right";
            }

            //CHECK TILE COLLISION

            collisionOn = false;
            gp.cChecker.checkTile(this);


            //CHECK OBJECT COLLISION

            int objIndex = gp.cChecker.checkObject(this, true);
            pickUpObject(objIndex);

            // CHECK NPC COLLISION
            int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
            interactNPC(npcIndex);

            // CHECK MONSTER COLLISION
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            contactMonster(monsterIndex);

            // CHECK EVENT
            
            //IF COLLISION IS FALSE, THE PLAYER CAN MOVE
            if(collisionOn == false && keyH.enterPressed == false ){
                
                switch(direction){

                    case "up": worldY -= speed; break;
                    case "down": worldY += speed; break;
                    case "left": worldX -= speed; break;
                    case "right": worldX += speed; break;
                }
            }
            if ( keyH.enterPressed == true && attackCanceled == false ) {
                attacking = true;
                spriteCounter = 0;
            }

            attackCanceled = false;

            gp.keyH.enterPressed = false;

            spriteCounter++;
            spriteCounter1++;

            if (spriteCounter1 > 12) {
                if (spriteNum1 == 1) {
                    spriteNum1 = 2;
                }
                else if (spriteNum1 == 2) {
                    spriteNum1 = 3;
                }
                else if (spriteNum1 == 3){
                    spriteNum1 = 4;
                }
                else if (spriteNum1 == 4){
                    spriteNum1 = 5;
                }
                else if (spriteNum1 == 5){
                    spriteNum1 = 6;
                }
                else if (spriteNum1 == 6){
                    spriteNum1 = 7;
                }
                else if (spriteNum1 == 7){
                    spriteNum1 = 8;
                }
                else if (spriteNum1 == 8) {
                    spriteNum1 = 1;
                }

                spriteCounter1 = 0;

            }

            if (spriteCounter > 12) {
                if (spriteNum == 1) {
                    spriteNum = 2;
                }
                else if (spriteNum == 2) {
                    spriteNum = 3;
                }
                else if (spriteNum == 3){
                    spriteNum = 4;
                }
                else if (spriteNum == 4){
                    spriteNum = 5;
                }
                else if (spriteNum == 5){
                    spriteNum = 6;
                }
                else if (spriteNum == 6){
                    spriteNum = 7;
                }
                else if (spriteNum == 7){
                    spriteNum = 1;
                }
            
                spriteCounter = 0;

            }
        }
        else  {
            spriteNum = 1;
            spriteNum1 = 1;
        }

        // THIS NEEDS TO BE OUTSIDCE OF THE KEY IF STATEMENT
        if ( invincible == true ) {
            invincibleCounter++;
            if ( invincibleCounter > 60 ) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
    }

    public void attacking() {

        spriteCounter++;

        if ( spriteCounter <= 5 ) {
            spriteNum = 1;

            //Save the current worldX, worldY, solidArea
            int currentWorldX = worldX;
            int currentWorldY = worldY;
            int solidAreaWidth = solidArea.width;
            int solidAreaHeight = solidArea.height;

            //Adjust player's worldX/Y for attackArea

            switch(direction) {
                case "up": worldY -= attackArea.height + 5; break;
                case "down": worldY += attackArea.height + 5; break;
                case "left": worldX -= attackArea.width + 5; break;
                case "right": worldX += attackArea.width + 5; break;
            }

            //attackArea becomes solidArea
            solidArea.width = attackArea.width;
            solidArea.height = attackArea.height;

            //Check monster collision with the updated worldX, worldY and solidArea

            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            damageMonster(monsterIndex);

            worldX = currentWorldX;
            worldY = currentWorldY;
            solidArea.width = solidAreaWidth;
            solidArea.height = solidAreaHeight;
        }
        if( spriteCounter > 5 && spriteCounter <= 10){
            spriteNum = 2;
        }
        if ( spriteCounter > 10 && spriteCounter <= 20 ) {
            spriteNum = 3;
        }
        if( spriteCounter > 20 && spriteCounter <= 25){
            spriteNum = 4;
        }
        if ( spriteCounter > 25 && spriteCounter <= 30 ) {
            spriteNum = 5;
        }
        if ( spriteCounter > 30 && spriteCounter <= 35 ) {
            spriteNum = 4;
        }
        if ( spriteCounter > 35 && spriteCounter <= 40 ) {
            spriteNum = 3;
        }
        if ( spriteCounter > 40 && spriteCounter <= 45 ) {
            spriteNum = 2;
        }
        if ( spriteCounter > 45 && spriteCounter <= 50 ) {
            spriteNum = 1;
            spriteCounter = 0;
            attacking = false;
        }
    }

    public void pickUpObject(int i) {

        if (i != 999) {

            // PICKUP ONLY ITEMS
            if ( gp.obj[i].type == type_pickupOnly ) {

                String objectName = gp.obj[i].name;

                /*switch (objectName) {
                    case "Gem" -> {
                    gp.playSE(2);
                    gp.gameState = gp.cutsceneState;
                    gp.csManager.sceneNum = gp.csManager.ending;
                                  
                    }
                }*/
                gp.obj[i].use(this);
                gp.obj[i] = null;
            }
            // INVENTORY ITEMS
            else {
                String objectName = gp.obj[i].name;
            String text;

            
                if ( inventory.size() != maxInventorySize && gp.obj[i].type == gp.player.type_consumable) {

                inventory.add(gp.obj[i]);
                gp.playSE(2);
            switch (objectName) {
                
            case "Compas" -> {
                
                    gp.teleportation = true;
                    gp.ui.addMessage("Teleportation unlocked!");
                    gp.obj[i] = null;
                    break;
                }
            case "Potion" -> {
                gp.obj[i] = null;
                gp.ui.addMessage("You've got a potion!");
                break;
            }
            case "Boots" -> {
                gp.obj[i] = null;
                gp.ui.addMessage("Speed increased!");
                gp.player.speed += 1;
                gp.bootsUnlocked = true;   
                break;               
            }
            }
            }
            switch (objectName) {

                case "Door" -> {
                    if(hasKey > 0){
                        gp.playSE(1);
                        gp.obj[i] = null;
                        hasKey--;
                        gp.ui.addMessage("You opened the door!");

                    }
                    else {
                        int counter = 0;
                        if ( counter > 180 ) {
                            gp.ui.addMessage("You need a key!");
                            counter = 0;
                        }
                        else {
                            counter++;
                        }
                        
                    }
                    break;
                }
                
                case "Chest" -> {
                    boolean opened = false;
                    if ( hasKey > 0 ) {
                        gp.playSE(1);
                        hasKey--;
                        opened = true;
                        //gp.obj[i] = null;
                        gp.obj[i] = new OBJ_Chest1(gp); gp.obj[i].worldX = (int) (43 * gp.tileSize); gp.obj[i].worldY = (int)(36 * gp.tileSize);
                        gp.ui.addMessage("You opened the chest!");
                        if ( opened == true ) {
                            gp.obj[99] = new OBJ_Compas(gp); gp.obj[99].worldX = (int) (44 * gp.tileSize); gp.obj[99].worldY = (int)(37 * gp.tileSize);  
                        }
                    }
                    else if (counter <= 0 && hasKey == 0 ){
                        gp.ui.addMessage("You need a key!");
                        counter++;
                    }
                }
                case "Gem" -> {
                    if ( gp.player.level >= 3 ) {
                        gp.playSE(2);
                        gp.obj[i] = null;
                        gp.gameState = gp.cutsceneState;
                        gp.csManager.sceneNum = gp.csManager.ending;
                    }
                    else {
                        dialogues[1][0] = "You need level 3 to obtain me!";
                        startDialogue(this, 1);
                    }
                                  
                }
                case "Key" -> {
                    gp.playSE(2);
                    hasKey++;
                    gp.obj[i] = null;
                    gp.ui.addMessage("You got a key!");
                }
            }
            }
            }
            
        }
    public void interactNPC ( int i ) {

        if ( gp.keyH.enterPressed == true ) {

        if ( i != 999 ) {
                attackCanceled = true;
                gp.npc[i].speak();
        }
        else {
            gp.playSE(10);
            attacking = true;
        }
    }
}

    public void contactMonster ( int i ) {

        if ( i != 999 ) {

            if ( invincible == false ) {
                gp.playSE(8);

                int damage = gp.monster[i].attack - defense;
                if ( damage < 0 ) {
                    damage = 0;
                }

                gp.player.life -= damage;
                invincible = true;
            }
        }
    }

    public void damageMonster(int i) {

        if(i != 999){
            if(gp.monster[i].invincible == false){

                gp.playSE(9);

                int damage = attack - gp.monster[i].defense;

                if ( damage < 0 ) {
                    damage = 0;
                }

                gp.monster[i].life -= damage;
                gp.ui.addMessage(damage + " damage!");

                gp.monster[i].invincible = true;
                gp.monster[i].damageReaction();

                if(gp.monster[i].life <= 0){
                    gp.monster[i].dying = true;
                    gp.ui.addMessage("Killed the " + gp.monster[i].name + "!");
                    gp.ui.addMessage("Exp " + gp.monster[i].exp + "!");
                    exp += gp.monster[i].exp;
                    checkLevelUp();
                }
            }
        }
    }
    public void checkLevelUp() {

        if ( exp >= nextLevelExp ) {

            level++;
            nextLevelExp = nextLevelExp * 2;
            maxLife++;
            strenght++;
            dexterity++;
            attack = getAttack();
            //defense = getDefense();

            gp.playSE(11);
            
            dialogues[0][0] = "You are level " + level + " now!\n"
                + "You feel stronger!";
            startDialogue(this, 0);

        }
    }
    
    public void draw(Graphics2D g2) {

       // g2.setColor(Color.white);
       // g2.fillRect(x, y, gp.tileSize, gp.tileSize);

        BufferedImage image = null;
        int tempScreenX = screenX;
        int tempScreenY = screenY;
        
        switch(direction) {
            case "up":
            if(attacking == false){
                if(spriteNum == 1) {image = up1;}
                if(spriteNum == 2) {image = up2;}
                if(spriteNum == 3) {image = up3;}
                if(spriteNum == 4) {image = up4;}
                if(spriteNum == 5) {image = up5;}
                if(spriteNum == 6) {image = up6;}
                if(spriteNum == 7) {image = up7;}
                break;
            }
            if(attacking == true){
                tempScreenY = tempScreenY - gp.tileSize;
                if(spriteNum == 1){image = attackUp1;}
                if(spriteNum == 2){image = attackUp2;}
                if(spriteNum == 3){image = attackUp3;}
                if(spriteNum == 4){image = attackUp4;}
                if(spriteNum == 5){image = attackUp5;}
                break;
            }
            case "down":
            
            if(attacking == false){
                if(spriteNum == 1) {image = down1;}
                if(spriteNum == 2) {image = down2;}
                if(spriteNum == 3) {image = down3;}
                if(spriteNum == 4) {image = down4;}
                if(spriteNum == 5) {image = down5;}
                if(spriteNum == 6) {image = down6;}
                if(spriteNum == 7) {image = down7;}
                break;
            }

            if(attacking == true){
                tempScreenY = screenY;
                if(spriteNum == 1){image = attackDown1;}
                if(spriteNum == 2){image = attackDown2;}
                if(spriteNum == 3){image = attackDown3;}
                if(spriteNum == 4){image = attackDown4;}
                if(spriteNum == 5){image = attackDown5;}
                break;
            }
            case "left":

            if(attacking == false){
                if(spriteNum == 1) {image = left1;}
                if(spriteNum == 2) {image = left2;}
                if(spriteNum == 3) {image = left3;}
                if(spriteNum == 4) {image = left4;}
                if(spriteNum == 5) {image = left5;}
                if(spriteNum == 6) {image = left6;}
                if(spriteNum == 7) {image = left7;}
                if(spriteNum == 8) {image = left8;}
                break;
            }

            if(attacking == true){
                tempScreenX = screenX - gp.tileSize;
                if(spriteNum == 1){image = attackLeft1;}
                if(spriteNum == 2){image = attackLeft2;}
                if(spriteNum == 3){image = attackLeft3;}
                if(spriteNum == 4){image = attackLeft4;}
                if(spriteNum == 5){image = attackLeft5;}
                break;
            }
            case "right":

            if(attacking == false){
                if(spriteNum == 1) {image = right1;}
                if(spriteNum == 2) {image = right2;}
                if(spriteNum == 3) {image = right3;}
                if(spriteNum == 4) {image = right4;}
                if(spriteNum == 5) {image = right5;}
                if(spriteNum == 6) {image = right6;}
                if(spriteNum == 7) {image = right7;}
                if(spriteNum == 8) {image = right8;}
                break;
            }

            if(attacking == true){
                tempScreenX = screenX;
                if(spriteNum == 1){image = attackRight1;}
                if(spriteNum == 2){image = attackRight2;}
                if(spriteNum == 3){image = attackRight3;}
                if(spriteNum == 4){image = attackRight4;}
                if(spriteNum == 5){image = attackRight5;}
                break;
            }
        }

        if ( invincible == true ) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        }
        g2.drawImage(image, tempScreenX, tempScreenY, null);

        // RESET ALPHA
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        /*g2.setFont(new Font("Arial", Font.PLAIN, 26));
        g2.setColor(Color.white);
        g2.drawString("Invincible: " + invincibleCounter, 10, 400);*/
        //g2.setColor(Color.red);
        //g2.drawRect(attackArea.x, attackArea.y, attackArea.width, attackArea.height);
    }
    public void selectItem() {

        int itemIndex = gp.ui.getItemIndexOnSlot();

        if ( itemIndex < inventory.size() ) {

            Entity selectedItem = inventory.get(itemIndex);

            if ( selectedItem.type == type_consumable ) {

                selectedItem.use(this);
                inventory.remove(itemIndex);
            }
        }
    }
}
 