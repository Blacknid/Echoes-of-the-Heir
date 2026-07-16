package entity;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;

import gfx.Sprite;

import audio.SFX;
import main.GamePanel;
import util.ObjectPool.Poolable;

public class Projectile extends Entity implements Poolable {

    Entity user;

    public Projectile(GamePanel gp){
        super(gp);
    }

    public void set(int worldX, int worldY, int direction, boolean alive, Entity user) {

        this.worldX = worldX;
        this.worldY = worldY;
        this.direction = direction;
        this.alive = alive;
        this.user = user;
        this.life = this.maxLife;
    }

    public void update() {
        int moveSpeed = speed;
        // Use current-position collision for projectiles so hitboxes match rendered sprites.
        speed = 0;
        
        if(user == gp.player) {
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            if(monsterIndex != 999){
                // projectiles also impart knockback based on their speed
                gp.player.damageMonster(monsterIndex, this.attack);
                if (monsterIndex != 999) {
                    int kb = Math.max(1, this.speed / 3);
                    gp.player.knockBack(gp.monster[monsterIndex], kb, worldX, worldY);
                }
                if (user.projectile != null) {
                    generateParticle(user.projectile, gp.monster[monsterIndex]);
                }
                alive = false;
            }
            // Check NPCs as well (Eye is implemented as an NPC). Only apply
            // projectile damage to NPCs named "Eye" so other NPCs are unaffected.
            int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
            if (npcIndex != 999) {
                if (gp.npc[npcIndex].name != null && gp.npc[npcIndex].name.equals("Eye")) {
                    if (!gp.npc[npcIndex].invincible) {
                        gp.playSE(SFX.GOT_GEM);
                        int kb = Math.max(1, this.speed / 3);
                        gp.player.knockBack(gp.npc[npcIndex], kb, worldX, worldY);
                        int damage = this.attack - gp.npc[npcIndex].defense;
                        if (damage < 0) damage = 0;
                        gp.npc[npcIndex].life -= damage;
                        if (user.projectile != null) {
                            generateParticle(user.projectile, gp.npc[npcIndex]);
                        }
                        gp.npc[npcIndex].invincible = true;
                        gp.npc[npcIndex].damageReaction();
                        if (gp.npc[npcIndex].life <= 0) {
                            gp.npc[npcIndex].dying = true;
                        }
                        alive = false;
                    }
                }
            }
        }
        if(user != gp.player){
            boolean contactPlayer = gp.cChecker.checkPlayer(this);
            if(contactPlayer && !gp.player.invincible){
                int damage = this.attack - gp.player.defense;
                damage = (int)(damage * gp.player.damageTakenMultiplier);
                if (damage < 1) {
                    damage = 1;
                }
                int knockbackPower = Math.max(1, (this.attack + 1) / 2);
                gp.player.onHitByEnemy(damage, worldX, worldY, knockbackPower);
                if (user.projectile != null) {
                    generateParticle(user.projectile, gp.player);
                }
                alive = false;
            }
        }

        speed = moveSpeed;

        switch(direction) {
            case DIR_UP:    worldY -= speed; break;
            case DIR_DOWN:  worldY += speed; break;
            case DIR_LEFT:  worldX -= speed; break;
            case DIR_RIGHT: worldX += speed; break;
        }

        life--;
        if(life <= 0){
            alive = false;
        }

        spriteCounter++;
        if(spriteCounter > 12){
            if(spriteNum == 1) {
                spriteNum = 2;
            }
            else if(spriteNum == 2){
                spriteNum = 1;
            }
            spriteCounter = 0;
        }
    }
    public boolean haveResource(Entity user) {

        boolean haveResource = false;
        return haveResource;

    }
    /**
     * Rotate a sprite by a multiple of 90° into a new texture, the load-time GPU-native
     * replacement for the old BufferedImage+Graphics2D.rotate. Callers (Eye, OBJ_Arrow) only ever
 * use ±90/180/270, so we rotate the source Pixmap exactly by transpose/flip, no sampling blur.
     */
    public static Sprite rotateImage(Sprite img, double degrees) {
        Texture src = img.texture();
        // Headless (server): nothing to rotate and nothing to rotate it with. Return a sprite of
        // the rotated SIZE so any dimension math downstream still agrees with the client's.
        if (src == null) {
            int deg0 = ((int) Math.round(degrees) % 360 + 360) % 360;
            boolean swap = (deg0 == 90 || deg0 == 270);
            return Sprite.headless(swap ? img.getHeight() : img.getWidth(),
                                   swap ? img.getWidth()  : img.getHeight());
        }
        TextureData td = src.getTextureData();
        if (!td.isPrepared()) td.prepare();
        Pixmap in = td.consumePixmap();
        int w = in.getWidth(), h = in.getHeight();

        int deg = ((int) Math.round(degrees) % 360 + 360) % 360; // normalize to 0/90/180/270
        Pixmap out;
        if (deg == 90 || deg == 270) out = new Pixmap(h, w, Pixmap.Format.RGBA8888);
        else                         out = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        out.setBlending(Pixmap.Blending.None);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = in.getPixel(x, y);
                switch (deg) {
                    case 90  -> out.drawPixel(h - 1 - y, x, rgba);
                    case 180 -> out.drawPixel(w - 1 - x, h - 1 - y, rgba);
                    case 270 -> out.drawPixel(y, w - 1 - x, rgba);
                    default  -> out.drawPixel(x, y, rgba);
                }
            }
        }
        Texture tex = gfx.GdxTextureUtil.managedFromPixmap(out);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return new Sprite(tex);
    }
    public void subtractResource(Entity user) {}

    @Override
    public void reset() {
        alive = false;
        user = null;
        life = 0;
        worldX = 0;
        worldY = 0;
        direction = DIR_DOWN;
        spriteNum = 1;
        spriteCounter = 0;
    }
}
