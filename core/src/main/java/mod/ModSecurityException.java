package mod;

/**
 * Thrown by {@link SecurityGate} when a Lua mod tries to reach a sealed class, field or member
 * (auth, multiplayer/save servers, encrypted saves, cryptographic keys). Unchecked so it propagates
 * cleanly out of the LuaJ call stack and is reported as a mod error, not a game crash.
 */
public class ModSecurityException extends RuntimeException {
    public ModSecurityException(String message) {
        super(message);
    }
}
