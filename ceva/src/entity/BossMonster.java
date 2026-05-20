package entity;

import main.GamePanel;

/**
 * Boss entity with a two-phase AI system.
 * Phase 1: Normal combat behavior (uses aiBehavior from DataDrivenMonster).
 * Phase 2: Triggered at HP threshold — speed boost, optional behavior change.
 *
 * Tiled properties (read by MapObjectLoader):
 *   bossId             (int)     1–4, maps to gp.boss1Defeated…boss4Defeated
 *   phase2Threshold    (float)   HP fraction to trigger phase 2 (default 0.5)
 *   phase2SpeedBoost   (int)     added to speed in phase 2 (default 1)
 *   phase2Behavior     (String)  AI swap on phase 2 (optional, e.g. "ranged_archer")
 */
public class BossMonster extends Entity {

    public int bossId = 0;  // 1–4
    public String onDeathQuestId = ""; // quest to progress when this boss dies
    private int phase = 1;
    private float phase2Threshold = 0.5f;
    private int phase2SpeedBoost = 1;

    // Delegate: the wrapped DataDrivenMonster that handles actual AI
    private final Entity innerMonster;

    public BossMonster(GamePanel gp, Entity innerMonster, int bossId,
                       float phase2Threshold, int phase2SpeedBoost) {
        super(gp);
        this.innerMonster = innerMonster;
        this.bossId = bossId;
        this.phase2Threshold = phase2Threshold;
        this.phase2SpeedBoost = phase2SpeedBoost;

        copyFromInner();
    }

    private void copyFromInner() {
        this.type = innerMonster.type;
        this.name = innerMonster.name;
        this.collision = innerMonster.collision;
        this.defaultSpeed = innerMonster.defaultSpeed;
        this.speed = innerMonster.speed;
        this.walkFrameCount = innerMonster.walkFrameCount;
        this.maxLife = innerMonster.maxLife;
        this.life = innerMonster.life;
        this.attack = innerMonster.attack;
        this.defense = innerMonster.defense;
        this.exp = innerMonster.exp;
        this.aggroRange = innerMonster.aggroRange;
        this.fleeDuration = innerMonster.fleeDuration;
        this.solidArea.x = innerMonster.solidArea.x;
        this.solidArea.y = innerMonster.solidArea.y;
        this.solidArea.width = innerMonster.solidArea.width;
        this.solidArea.height = innerMonster.solidArea.height;
        this.solidAreaDefaultX = innerMonster.solidAreaDefaultX;
        this.solidAreaDefaultY = innerMonster.solidAreaDefaultY;
        this.walkFrames = innerMonster.walkFrames;
        this.projectile = innerMonster.projectile;
        this.hpBarOn = true;
    }

    @Override
    public void setAction() {
        innerMonster.worldX = this.worldX;
        innerMonster.worldY = this.worldY;
        innerMonster.direction = this.direction;
        innerMonster.onPath = this.onPath;
        innerMonster.fleeing = this.fleeing;
        innerMonster.fleeCounter = this.fleeCounter;
        innerMonster.speed = this.speed;
        innerMonster.life = this.life;

        innerMonster.setAction();

        this.direction = innerMonster.direction;
        this.onPath = innerMonster.onPath;
        this.fleeing = innerMonster.fleeing;
        this.fleeCounter = innerMonster.fleeCounter;
        this.speed = innerMonster.speed;
    }

    @Override
    public void damageReaction() {
        innerMonster.life = this.life;
        innerMonster.damageReaction();

        if (phase == 1 && life <= maxLife * phase2Threshold) {
            phase = 2;
            speed = defaultSpeed + phase2SpeedBoost;
            innerMonster.speed = speed;
            gp.screenShake.shakeMedium();
            gp.triggerHitstop(8);
        }

        this.direction = innerMonster.direction;
        this.onPath = innerMonster.onPath;
        this.fleeing = innerMonster.fleeing;
    }

    /** Called by GamePanel when this boss dies — sets the corresponding defeated flag. */
    public void onDeath() {
        switch (bossId) {
            case 1 -> gp.boss1Defeated = true;
            case 2 -> gp.boss2Defeated = true;
            case 3 -> gp.boss3Defeated = true;
            case 4 -> gp.boss4Defeated = true;
        }
        int count = 0;
        if (gp.boss1Defeated) count++;
        if (gp.boss2Defeated) count++;
        if (gp.boss3Defeated) count++;
        if (gp.boss4Defeated) count++;
        gp.storyAct = Math.max(gp.storyAct, count);
        if (!onDeathQuestId.isEmpty()) gp.questManager.progress(onDeathQuestId, 1);
    }

    public int getPhase() { return phase; }
}
