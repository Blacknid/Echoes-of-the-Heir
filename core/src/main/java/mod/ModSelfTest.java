package mod;

/**
 * Standalone client-side verification of the modding system, runnable without a GPU/window. It
 * boots the mod loader the way the real client does (NOT headless — the authoritative server never
 * loads mods), then asserts: the example mod loaded and registered content, the security gate
 * refuses every sealed surface, and local-only storage round-trips. Run via
 * {@code ./gradlew :core:runModSelfTest}.
 *
 * <p>This is a developer harness, not shipped game code path — nothing calls it at runtime.
 */
public final class ModSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // A minimal libGDX files backend so ModStorage (Gdx.files.local) works outside a real app.
        com.badlogic.gdx.Gdx.files = new com.badlogic.gdx.backends.headless.HeadlessFiles();

        System.out.println("=== ModSelfTest: booting client-side mod loader ===");
        ModLoader.preInit();   // client path (Headless is NOT enabled here)

        // 1) Example mod content registered.
        check("example mod registered 'frost_wisp' monster",
                ModContentRegistry.hasMonster("frost_wisp"));
        check("frost_wisp has expected maxLife=12",
                "12".equals(ModContentRegistry.monster("frost_wisp") != null
                        ? ModContentRegistry.monster("frost_wisp").get("maxLife") : null));

        // 2) MonsterFactory now sees the mod monster as known.
        check("MonsterFactory.isKnown('frost_wisp') is true via registry",
                data.MonsterFactory.isKnown("frost_wisp"));
        check("MonsterFactory.defStat reads mod stat", data.MonsterFactory.defStat("frost_wisp", "attack", -1) == 3);

        // 3) The security gate refuses every sealed surface.
        checkSealed("data.CloudSaveService (save server)", "data.CloudSaveService");
        checkSealed("data.SaveLoad (encrypted saves)",     "data.SaveLoad");
        checkSealed("main.MultiplayerClient (networking)", "main.MultiplayerClient");
        checkSealed("main.Main (license key holder)",      "main.Main");
        checkSealed("server.EngineServer (auth server)",   "server.EngineServer");
        checkSealed("platform.LicenseActivation (auth)",   "platform.LicenseActivation");
        checkSealed("javax.crypto.Cipher (crypto)",        "javax.crypto.Cipher");
        checkSealed("java.io.File (raw fs)",               "java.io.File");
        checkSealed("java.lang.Runtime (process)",         "java.lang.Runtime");

        // 4) A permitted gameplay class IS importable.
        checkAllowed("entity.Entity is importable", "entity.Entity");
        checkAllowed("main.GamePanel is importable", "main.GamePanel");

        // 5) Reachability seal: GamePanel.saveLoad field is sealed even though GamePanel is allowed.
        boolean fieldSealed;
        try {
            java.lang.reflect.Field f = main.GamePanel.class.getField("saveLoad");
            SecurityGate.assertFieldAccess(f);
            fieldSealed = false;                 // should have thrown
        } catch (ModSecurityException e) {
            fieldSealed = true;
        } catch (NoSuchFieldException e) {
            fieldSealed = false;
        }
        check("GamePanel.saveLoad field is sealed (reachability)", fieldSealed);

        // 6) Type- and value-level seal (the check the bridge runs on every return value / field
        // read). No networked object is constructed; isSealedType/isSealedValue read the class only.
        check("isSealedType(CloudSaveService) is true", SecurityGate.isSealedType(data.CloudSaveService.class));
        check("isSealedType(Entity) is false (gameplay allowed)", !SecurityGate.isSealedType(entity.Entity.class));
        check("assignability seal: a subclass of a sealed class is sealed too",
                SecurityGate.isSealedType(SealedSub.class));

        System.out.println("=== ModSelfTest: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else      { failed++; System.out.println("  FAIL  " + name); }
    }

    private static void checkSealed(String name, String fqn) {
        boolean sealed;
        try { JavaBridge.importClass(fqn); sealed = false; }
        catch (ModSecurityException e) { sealed = true; }
        catch (Throwable t) { sealed = true; } // any refusal counts as sealed
        check("sealed: " + name, sealed);
    }

    private static void checkAllowed(String name, String fqn) {
        boolean ok;
        try { ok = !JavaBridge.importClass(fqn).isnil(); }
        catch (Throwable t) { ok = false; }
        check("allowed: " + name, ok);
    }

    /**
     * A subclass of a sealed class, used only via {@code SealedSub.class} to prove the assignability
     * seal (a subtype of a sealed class is itself sealed even under a different name). Never
     * instantiated, so the parent's constructor side effects never run.
     */
    static final class SealedSub extends main.ServerListManager {}
}
