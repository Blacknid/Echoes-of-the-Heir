package coop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state for one LAN co-op boss session — either the HOST side ({@link BossCoopHostServer}
 * drives it) or the JOINER side ({@link BossCoopClient} drives it). The UI (see
 * {@code ui.UI}'s boss-invite screens) reads this directly; it never talks to the socket layer.
 *
 * <p>Lifecycle: {@code WAITING} (matchmaking room, players trickling in) → host clicks "Proceed"
 * → {@code ACTIVE} (fight in progress) → {@code ENDED} (boss dead or session torn down) → the
 * screen closes and, on the joiner side, {@link coop.PlayerLoadout} restores their real loadout.
 */
public final class BossCoopSession {

    public enum State { WAITING, ACTIVE, ENDED }
    public enum Role { HOST, JOINER }

    private final Role role;
    private volatile State state = State.WAITING;
    private volatile String statusMessage = "";
    private volatile int bossId;

    // Usernames of everyone currently in the session (host included), in join order.
    private final List<String> players = new CopyOnWriteArrayList<>();

    public BossCoopSession(Role role, String hostUsername, int bossId) {
        this.role = role;
        this.bossId = bossId;
        players.add(hostUsername);
    }

    public Role role() { return role; }
    public State state() { return state; }
    public String statusMessage() { return statusMessage; }
    public int bossId() { return bossId; }
    public int playerCount() { return players.size(); }
    public List<String> players() { return new ArrayList<>(players); }

    /** Package-visible from any transport implementation ({@code coop.*} on desktop, {@code
     *  androidlauncher.coop.*} on mobile) — the UI only ever reads this session, never mutates it. */
    public void addPlayer(String username) {
        if (!players.contains(username)) players.add(username);
    }

    public void removePlayer(String username) {
        players.remove(username);
    }

    public void setState(State s) { this.state = s; }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }

    public boolean isFull() { return players.size() >= BossCoopProtocol.MAX_PLAYERS; }
}
