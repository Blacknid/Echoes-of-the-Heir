package platform;

public interface LicenseCheck {
    boolean verifyCurrent();
    String getCachedMachineFp();
    String getCachedSignature();
    String getCachedKey();
    boolean isTampered();
}
