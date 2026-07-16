package platform;

public interface LicenseCheck {
    boolean verifyCurrent();
    /** Opaque server-side row pointer from ACTIVATE, presented on every later LOGIN. */
    String getActivationId();
    /** Base64(nonce_12 || AESGCM(license_key)), undecryptable without the server's enc_key. */
    String getEncBlob();
    String getCachedKey();
    boolean isTampered();
}
