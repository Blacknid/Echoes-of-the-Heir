package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import main.GamePanel;
import main.KeyHandler;
import object.OBJ_Arrow;
import object.OBJ_Shield_Wood;
import object.OBJ_Sword_Normal;
public class Player extends Entity {

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
        solidArea.x = 20;
        solidArea.y = 25;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        solidArea.width = 20;
        solidArea.height = 20;

        setDefaultValues();
    }

    public void setItems() {

        inventory.add( currentWeapon );
        inventory.add( currentShield );

     }

    public void restoreLifeAndMana() { life = maxLife; }

    public void setDefaultPositions() {
        worldX = (int)(gp.tileSize * 24.5);
        worldY = (int)(gp.tileSize * 15.5);
        direction = "down";
    }

    public void setDialogue() { 

        dialogues[0][0] = "You are level " + level + " now!\n"
                + "You feel stronger!";

    }

    public void setDefaultValues() {
        worldX = (int)(gp.tileSize * 71.5);
        worldY = (int)(gp.tileSize * 21.5);
        speed = 4;
        direction = "down";

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
        projectile = new OBJ_Arrow(gp);


        attack = getAttack();
        defense = getDefense();

        getPlayerImages();
        getPlayerAttackImages();
        setItems();
        setDialogue();
    }

    public void setPlayerStats(int life, int strenght, int dexterity, int speed) {
        this.maxLife = life;
        this.life = this.maxLife;
        this.strenght = strenght;
        this.dexterity = dexterity;
        this.speed = speed;
        this.attack = getAttack();
        this.defense = getDefense();
    }

    public int getAttack() { 
        
        attackArea = currentWeapon.attackArea;
        return attack = strenght * currentWeapon.attackValue; 
    
    }

    public int getDefense() { 
        
        return defense = dexterity * currentShield.defenseValue; 
    
    }

    public int getCurrentWeaponSlot() {

        int currentWeaponSlot = 0;

        for ( int i = 0; i < inventory.size(); i++ ) {

            if ( inventory.get(i) == currentWeapon ) {
                currentWeaponSlot = i;
            }
        }
        return currentWeaponSlot;
    }
    public int getCurrentShieldSlot() {

        int currentShieldSlot = 0;

        for ( int i = 0; i < inventory.size(); i++ ) {

            if ( inventory.get(i) == currentShield ) {
                currentShieldSlot = i;
            }
        }
        return currentShieldSlot;
    }
    /**
     * Adaugam un sistem de incarcare a unui spritesheet cu un numar variabil de cadre pe rand.
     */

    public void getPlayerImages() {
        // Specify frames per direction (randuri)
        int[] framesPerRow = {7, 8, 8, 7}; // down, left, right, up
        BufferedImage[][] frames = loadSheetVariable("/res/player/player-sheet", framesPerRow);

        // Assign up frames
        down1= frames[0][0];
        down2 = frames[0][1];
        down3 = frames[0][2];
        down4 = frames[0][3];
        down5 = frames[0][4];
        down6 = frames[0][5];
        down7 = frames[0][6];

        // Assign down frames
        left1 = frames[1][0];
        left2 = frames[1][1];
        left3 = frames[1][2];
        left4 = frames[1][3];
        left5 = frames[1][4];
        left6 = frames[1][5];
        left7 = frames[1][6];
        left8 = frames[1][7];

        // Assign left frames
        right1 = frames[2][0];
        right2 = frames[2][1];
        right3 = frames[2][2];
        right4 = frames[2][3];
        right5 = frames[2][4];
        right6 = frames[2][5];
        right7 = frames[2][6];
        right8 = frames[2][7];

        // Assign right frames
        up1 = frames[3][0];
        up2 = frames[3][1];
        up3 = frames[3][2];
        up4 = frames[3][3];
        up5 = frames[3][4];
        up6 = frames[3][5];
        up7 = frames[3][6];
    }

    public void getPlayerAttackImages() {
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

        // Handle attacking first
        if (attacking) {
            attacking();
        } else {

            // Determine direction based on keys
            if (keyH.upPressed) direction = "up";
            else if (keyH.downPressed) direction = "down";
            else if (keyH.leftPressed) direction = "left";
            else if (keyH.rightPressed) direction = "right";
        
            boolean moving = keyH.upPressed || keyH.downPressed || keyH.leftPressed || keyH.rightPressed;
        
            if (moving || keyH.enterPressed) {

                // COLLISION CHECKS
                collisionOn = false;
                gp.cChecker.checkTile(this);
                int objIndex = gp.cChecker.checkObject(this, true);
                pickUpObject(objIndex);
                int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
                interactNPC(npcIndex);
                int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
                contactMonster(monsterIndex);

                // CHECK EVENT
                gp.eHandler.checkEvent();

                // Move player if no collision
                if (!collisionOn && !keyH.enterPressed) {
                    switch(direction) {
                        case "up": worldY -= speed; break;
                        case "down": worldY += speed; break;
                        case "left": worldX -= speed; break;
                        case "right": worldX += speed; break;
                    }
                }

                // Start attack if enter pressed
                if (keyH.enterPressed && !attackCanceled) {
                    attacking = true;
                    spriteCounter = 0;
                }

                attackCanceled = false;
                keyH.enterPressed = false;
                // Update animation sprites
                updateSprite();

                } else {
                    // Player idle
                    spriteNum = 1;
                    spriteNum1 = 1;
                }
            
                if(gp.keyH.shotKeyPressed == true && projectile.alive == false){
                    
                    //SET DEFAULT COORDINATES, DIRECTIONS AND USER
                    projectile.set(worldX, worldY, direction, true, this);

                    //ADD TO ARRAY
                    gp.projectilesList.add(projectile);

                    //gp.playSE(12);
                }

            // Handle invincibility timer
            if (invincible) {
                invincibleCounter++;
                if (invincibleCounter > 60) {
                    invincible = false;
                    invincibleCounter = 0;
                }
            }
        }


        
    }




    private void updateSprite() {
    spriteCounter++;
    spriteCounter1++;

    // Movement animation - speed

    if (spriteCounter1 > 8) {
        spriteNum1 = (spriteNum1 % 8) + 1; // loops 1-8
        spriteCounter1 = 0;
    }

    if (spriteCounter > 7) {
        spriteNum = (spriteNum % 7) + 1; // loops 1-7
        spriteCounter = 0;
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
            damageMonster(monsterIndex, attack);

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

            // PICKUP ONLY OBJECTS

            if (gp.obj[i].type == type_pickupOnly) {

                attackCanceled = true;
                if ( gp.obj[i].use(this) == true )
                {
                    gp.obj[i] = null;
                }
                else
                    return;
            
            }

            // INTERACTABLE OBJECTS

            else if ( gp.obj[i].type == type_obstacle ) {

                if(keyH.enterPressed == true) {
                    attackCanceled = true;
                    gp.obj[i].interact();
                }
            }

            // OBTAINABLE OBJECTS

            else if ( canObtainItem(gp.obj[i]) == true ) {

                gp.playSE(2);

                String objectName = gp.obj[i].name;

                switch (objectName) {

                    case "Compas" -> {

                        gp.teleportation = true;
                        gp.ui.addMessage("Teleportation unlocked!", Color.WHITE);
                        break;

                    }

                    case "Potion" -> {

                        gp.obj[i] = null;
                        gp.ui.addMessage("You've got a potion!", Color.WHITE);
                        break;

                    }

                    case "Boots" -> {

                        gp.obj[i] = null;
                        gp.ui.addMessage("Speed increased!", Color.WHITE);
                        gp.player.speed += 1;
                        gp.bootsUnlocked = true;
                        break;
                    }

                    case "Key" -> {
                        gp.playSE(2);
                        hasKey++;
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a key!", Color.WHITE);
                    }

                    case "Spell book" -> {
                        gp.playSE(2);
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a new weapon!", Color.WHITE);
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

        if (i != 999 && !invincible) {

            gp.playSE(8);

            int damage = gp.monster[i].attack - defense;
            if (damage < 1) damage = 1;

            life -= damage;
            invincible = true;
        }
    }

    public void damageMonster(int i, int attack) {

    if (i != 999) {
        if (gp.monster[i].invincible == false) {

            gp.playSE(5); // Play hit sound

            int damage = attack - gp.monster[i].defense;
            if (damage < 0) {
                damage = 0;
            }

            gp.monster[i].life -= damage;
            gp.ui.addMessage(damage + " damage!", Color.WHITE);
            gp.monster[i].invincible = true;
            gp.monster[i].damageReaction();

            if (gp.monster[i].life <= 0) {
                gp.monster[i].dying = true;
                gp.ui.addMessage("Killed the " + gp.monster[i].name + "!", Color.WHITE);
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
            life = maxLife;
            attack = getAttack();
            defense = getDefense();

            gp.playSE(11);
            
            setDialogue();
            startDialogue(this, 0);

        }
    }
    
    public void draw(Graphics2D g2) {

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

    }
    public void selectItem() {


        int itemIndex = gp.ui.getItemIndexOnSlot();

        if ( itemIndex < inventory.size() ) {

            Entity selectedItem = inventory.get(itemIndex);

            if(selectedItem.type == type_sword || selectedItem.type == type_book ) {

                currentWeapon = selectedItem;
                attack = getAttack();
            }

            else if ( selectedItem.type == type_shield ) {

                currentShield = selectedItem;
                defense = getDefense();
            }

            else if ( selectedItem.type == type_consumable ) {

                if (selectedItem.use(this) == true && selectedItem.name != "Potion") {
                    if(selectedItem.amount > 1 ) {
                        selectedItem.amount--;
                    }
                    else {
                        inventory.remove(itemIndex);
                    }
                }
            }
        }
    }
    public int searchItemInInventory ( String itemName ) {

        int itemIndex = 999;

        for ( int i = 0 ; i < inventory.size(); i++ ) {

            if ( inventory.get(i).name.equals(itemName) ) {
                itemIndex = i;
                break;
            }
        }
        return itemIndex;

    }
    public boolean canObtainItem ( Entity item ) {

        if (item == null) {
            System.err.println("canObtainItem() called with null item!");
            return false;
        }

        boolean canObtain = false;

        // CHECK IF ITEM IS STACKABLE
        if ( item.stackable == true ) {
        
            int index = searchItemInInventory( item.name );

            if ( index != 999 ) {
                inventory.get(index).amount++;
                canObtain = true;
            }
            else {
                if ( inventory.size() != maxInventorySize ) {
                    inventory.add(item);
                    canObtain = true;
                }
            }    
        }
        else {
            if ( inventory.size() != maxInventorySize ) {
                inventory.add(item);
                canObtain = true;
            }
        }
        return canObtain;
    }
}