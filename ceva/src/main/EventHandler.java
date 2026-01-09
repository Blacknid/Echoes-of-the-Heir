package main;

import entity.Entity;

public class EventHandler{

    GamePanel gp;
    EventRect eventRect[][];
    Entity eventMaster;

    int previousEventX, previousEventY;
    boolean canTouchEvent = true;

    public EventHandler(GamePanel gp) {
        this.gp = gp;

        eventMaster = new Entity(gp);

        eventRect = new EventRect[gp.maxWorldCol][gp.maxWorldRow];

        int col = 0;
        int row = 0;
        while ( col < gp.maxWorldCol && row < gp.maxWorldRow ) {

        eventRect[col][row] = new EventRect();
        eventRect[col][row].x = 23;
        eventRect[col][row].y = 23;
        eventRect[col][row].width = 2;
        eventRect[col][row].height = 2;
        eventRect[col][row].eventRectDefaultX = eventRect[col][row].x;
        eventRect[col][row].eventRectDefaultY = eventRect[col][row].y;

        col++;
        if ( col == gp.maxWorldCol ) {
            col = 0;
            row++;
            }
        }
        setDialogue();
    }
    public void setDialogue() {
        
        eventMaster.dialogues[0][0] = "Teleport!";
        eventMaster.dialogues[0][1] = "[I should search for another key]";
        eventMaster.dialogues[1][0] = "WHAT JUST HAPPEND?";
        eventMaster.dialogues[1][1] = "[Also, i feel like i'm getting closer to something strong]";
        eventMaster.dialogues[2][0] = "I feel something strange.";
        eventMaster.dialogues[2][1] = "[I should search around]";
    }

    public void checkEvent() {

        // CHECK IF THE PLAYER CHARACTER IS MORE THAN 1  TILE AWAY FROM THE LAST EVENT
        int xDistance = Math.abs ( gp.player.worldX - previousEventX );
        int yDistance = Math.abs ( gp.player.worldY - previousEventY );
        int distance = Math.max ( xDistance, yDistance );
        if ( distance > gp.tileSize ) {
            canTouchEvent = true;
        }

        if ( gp.teleportation == true ) {
            if ((hit(38, 22, "right") || hit(38, 22, "left")) == true) {teleport( 30 , 60,  gp.dialogueState);
            }
            if ((hit(80, 19, "up") || hit(80, 19, "down")) == true) {Island2(87, 59, gp.dialogueState);
            }
        }
        if ( gp.bootsUnlocked == true ) {
            if ((hit(89, 67, "up") || hit(89, 67, "down") || (hit(89, 67, "right") || (hit(89, 67, "left")) == true))) { Island3(36, 72, gp.dialogueState);

            }
        }
    }
    public boolean hit(int col, int row, String reqDirection) {

        boolean hit = false;

        gp.player.solidArea.x = gp.player.worldX + gp.player.solidArea.x;
        gp.player.solidArea.y = gp.player.worldY + gp.player.solidArea.y;
        eventRect[col][row].x = col * gp.tileSize + eventRect[col][row].x;
        eventRect[col][row].y = row * gp.tileSize + eventRect[col][row].y;

        if ( gp.player.solidArea.intersects(eventRect[col][row]) && eventRect[col][row].eventDone == false ) {
            if ( gp.player.direction.contentEquals(reqDirection) || reqDirection.contentEquals("any")) {
                hit = true;

                previousEventX = gp.player.worldX;
                previousEventY = gp.player.worldY;
            }
        }

        gp.player.solidArea.x = gp.player.solidAreaDefaultX;
        gp.player.solidArea.y = gp.player.solidAreaDefaultY;
        eventRect[col][row].x = eventRect[col][row].eventRectDefaultX;
        eventRect[col][row].y = eventRect[col][row].eventRectDefaultY;

        return hit;
    }
    public void teleport( int col, int row, int gameState ) {

        eventMaster.startDialogue(eventMaster, 0);
        gp.player.attackCanceled = true;
        gp.player.worldX = gp.tileSize * 73;
        gp.player.worldY = gp.tileSize * 27;
    }
    public void Island2( int col, int row, int gameState ) {

        eventMaster.startDialogue(eventMaster, 1);
        gp.player.attackCanceled = true;
        gp.player.worldX = gp.tileSize * 87;
        gp.player.worldY = gp.tileSize * 59;
        gp.player.direction = "down";
    }
    public void Island3 ( int col, int row, int gameState ) {

        eventMaster.startDialogue(eventMaster, 2);
        gp.player.attackCanceled = true;
        gp.player.worldX = gp.tileSize * 36;
        gp.player.worldY = gp.tileSize * 72;
        gp.player.direction = "left";
    }
}
