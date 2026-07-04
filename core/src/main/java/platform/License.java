package platform;

/** Read-only license surface shared code uses instead of depending on a platform-specific impl. */
public final class License {
    private License() {}

    private static volatile LicenseCheck active;

    public static void set(LicenseCheck check) { active = check; }

    public static boolean verifyCurrent() { return active != null && active.verifyCurrent(); }
    public static String getCachedMachineFp() { return active == null ? null : active.getCachedMachineFp(); }
    public static String getCachedSignature() { return active == null ? null : active.getCachedSignature(); }
    public static String getCachedKey() { return active == null ? null : active.getCachedKey(); }
    public static boolean isTampered() { return active != null && active.isTampered(); }
}
