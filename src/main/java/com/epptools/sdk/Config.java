package com.epptools.sdk;

import com.epptools.sdk.exception.ConfigException;

import java.util.Collections;
import java.util.List;

/**
 * Immutable connection settings for an EPP session. EPP is strict RFC EPP over TLS, conventionally on port 700.
 *
 * <p>Many registries need NO client certificate; {@code clientCert}/{@code clientKey}/{@code clientKeyPassphrase}
 * are for the ones that require mutual TLS. When {@code objUris}/{@code extUris} are left null the client logs in
 * advertising exactly the services the server greeting offers, so it is never rejected for an unsupported service.
 *
 * <p>Built through {@link Builder}: {@code Config.builder("epp.example", "clid", "secret").port(700).build()}.
 * The password and key passphrase are excluded from {@link #toString()} so a debug log never leaks them.
 */
public final class Config {
    public final String host;
    public final String clid;
    public final String password;
    public final int port;
    public final String lang;
    public final double connectTimeout;
    public final double readTimeout;
    public final boolean verifyPeer;
    public final boolean verifyPeerName;
    /** CA bundle that signs the SERVER certificate (private-CA / self-signed endpoint). PEM path, or null. */
    public final String caFile;
    /** Your (registrar) client certificate - only when mutual TLS is required. PEM path, or null. */
    public final String clientCert;
    /** Your client private key. PEM path. May be null when bundled in {@code clientCert}. */
    public final String clientKey;
    /** Passphrase for an encrypted client private key, if any. Kept out of toString() too. */
    public final String clientKeyPassphrase;
    /** Override the login objURIs; null = use the greeting's. */
    public final List<String> objUris;
    /** Override the login extURIs; null = use the greeting's. */
    public final List<String> extUris;
    /** Prefix for auto-generated client transaction ids (clTRID). */
    public final String clTRIDPrefix;
    /**
     * Override the registry's own extension namespace. null - the normal case - discovers it from the greeting.
     * A wrong value is not a validation error, it is silence: an extension sent under a namespace the server does
     * not recognise is IGNORED, not rejected, so the licence or price is simply absent and nothing says why.
     */
    public final String registryExtUri;
    /** Override for the account-balance extension namespace; null discovers it from the greeting. */
    public final String registryBalanceUri;
    /**
     * Take part in the Login Security extension (RFC 8807) when the server offers it. A server returns its
     * security events only to a client that SENT the block, so a client that never sends it never hears the
     * warning. Set false to stay off; it is then used only where unavoidable - a password over 16 characters.
     */
    public final boolean loginSecurity;

    private Config(Builder b) {
        if (b.host == null || b.host.isEmpty()) {
            throw new ConfigException("Config: host is required");
        }
        if (b.clid == null || b.clid.isEmpty()) {
            throw new ConfigException("Config: clid is required");
        }
        if (b.password == null) {
            throw new ConfigException("Config: password is required");
        }
        this.host = b.host;
        this.clid = b.clid;
        this.password = b.password;
        this.port = b.port;
        this.lang = b.lang;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.verifyPeer = b.verifyPeer;
        this.verifyPeerName = b.verifyPeerName;
        this.caFile = b.caFile;
        this.clientCert = b.clientCert;
        this.clientKey = b.clientKey;
        this.clientKeyPassphrase = b.clientKeyPassphrase;
        this.objUris = b.objUris == null ? null : Collections.unmodifiableList(b.objUris);
        this.extUris = b.extUris == null ? null : Collections.unmodifiableList(b.extUris);
        this.clTRIDPrefix = b.clTRIDPrefix;
        this.registryExtUri = b.registryExtUri;
        this.registryBalanceUri = b.registryBalanceUri;
        this.loginSecurity = b.loginSecurity;
    }

    public static Builder builder(String host, String clid, String password) {
        return new Builder(host, clid, password);
    }

    @Override
    public String toString() {
        // password and clientKeyPassphrase deliberately omitted.
        return "Config{host=" + host + ", clid=" + clid + ", port=" + port + ", lang=" + lang
                + ", verifyPeer=" + verifyPeer + ", loginSecurity=" + loginSecurity + "}";
    }

    /** Fluent builder. Only host/clid/password are required; everything else has an RFC-sensible default. */
    public static final class Builder {
        private final String host;
        private final String clid;
        private final String password;
        private int port = 700;
        private String lang = "en";
        private double connectTimeout = 10.0;
        private double readTimeout = 30.0;
        private boolean verifyPeer = true;
        private boolean verifyPeerName = true;
        private String caFile;
        private String clientCert;
        private String clientKey;
        private String clientKeyPassphrase;
        private List<String> objUris;
        private List<String> extUris;
        private String clTRIDPrefix = "JAVA-SDK";
        private String registryExtUri;
        private String registryBalanceUri;
        private boolean loginSecurity = true;

        private Builder(String host, String clid, String password) {
            this.host = host;
            this.clid = clid;
            this.password = password;
        }

        public Builder port(int v) { this.port = v; return this; }
        public Builder lang(String v) { this.lang = v; return this; }
        public Builder connectTimeout(double v) { this.connectTimeout = v; return this; }
        public Builder readTimeout(double v) { this.readTimeout = v; return this; }
        public Builder verifyPeer(boolean v) { this.verifyPeer = v; return this; }
        public Builder verifyPeerName(boolean v) { this.verifyPeerName = v; return this; }
        public Builder caFile(String v) { this.caFile = v; return this; }
        public Builder clientCert(String v) { this.clientCert = v; return this; }
        public Builder clientKey(String v) { this.clientKey = v; return this; }
        public Builder clientKeyPassphrase(String v) { this.clientKeyPassphrase = v; return this; }
        public Builder objUris(List<String> v) { this.objUris = v; return this; }
        public Builder extUris(List<String> v) { this.extUris = v; return this; }
        public Builder clTRIDPrefix(String v) { this.clTRIDPrefix = v; return this; }
        public Builder registryExtUri(String v) { this.registryExtUri = v; return this; }
        public Builder registryBalanceUri(String v) { this.registryBalanceUri = v; return this; }
        public Builder loginSecurity(boolean v) { this.loginSecurity = v; return this; }

        public Config build() { return new Config(this); }
    }
}
