package entity;

import main.GamePanel;
import main.MultiplayerClient.RemotePlayerState;

/**
 * A live puppet {@link Entity} mirroring one remote player, either a TCP {@link main.MultiplayerClient}
 * peer or a BLE {@link main.BleMultiplayerSession} guest/host. GamePanel rebuilds these every tick from
 * the underlying {@link RemotePlayerState} maps (see GamePanel#syncRemotePlayerEntities) so remote
 * players are "real" entities to any system that scans gp.remotePlayerEntities, right now that's just
 * environment.Lightning's light gather, which is the whole point: giving a puppet lightSource/lightRadius
 * makes it glow exactly like the local player's torch, with zero special-casing in the lighting code.
 *
 * <p>Deliberately inert: {@link #update()} is a no-op because position/animation state is pushed in
 * from the outside (GamePanel copies the spline-interpolated x/y each frame) rather than being driven by
 * the normal AI/physics update loop that every other Entity subclass uses.
 */
public class RemotePlayerEntity extends Entity {

    /** Warm player-glow tint, matches Lightning.PLAYER_LIGHT_COLOR so guests read as "another player",
 * not a torch/prop. */
    private static final gfx.Color REMOTE_PLAYER_LIGHT_COLOR = new gfx.Color(255, 224, 170);

    public RemotePlayerEntity(GamePanel gp) {
        super(gp);
        this.lightSource = true;
        this.lightColor = REMOTE_PLAYER_LIGHT_COLOR;
        this.lightRadius = gp.eManager != null && gp.eManager.lightning != null
                ? gp.eManager.lightning.playerLightRadius
                : 2;
    }

    /** Copies interpolated world position + light radius from the current session state. Called once
     *  per tick from GamePanel; never call update() on this entity. */
    public void syncFrom(RemotePlayerState rp, long nowNs, int lightRadiusTiles) {
        float[] pos = rp.evalSpline(nowNs);
        this.worldX = Math.round(pos[0]);
        this.worldY = Math.round(pos[1]);
        this.direction = rp.direction;
        this.alive = true;
        this.lightRadius = lightRadiusTiles;
    }

    /** No-op: this entity's state is pushed from RemotePlayerState, not driven by its own AI/physics. */
    @Override
    public void update() {
    }
}
