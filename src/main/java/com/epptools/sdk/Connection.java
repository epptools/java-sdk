package com.epptools.sdk;

import com.epptools.sdk.exception.ConfigException;
import com.epptools.sdk.exception.ConnectionException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A TLS connection to an EPP server with RFC 5734 framing. Terminal on the first read or write failure: a
 * broken transfer leaves the byte stream at an unknown offset, so the next command would read the previous
 * command's response - an off-by-one over billable transforms. From then on every call throws rather than
 * resuming on a stream that cannot be trusted; call {@link #open()} to start a fresh connection.
 */
public final class Connection implements Transport {
    private static final int MAX_FRAME = 1_048_576; // 1 MiB guard against a runaway length prefix

    private final Config config;
    private SSLSocket sock;
    private OutputStream out;
    private DataInputStream in;
    private String fatal; // the reason the session died, once a read/write has failed

    public Connection(Config config) {
        this.config = config;
    }

    @Override
    public void open() {
        fatal = null; // a Connection may be reopened after a failure
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(config.host, config.port), (int) (config.connectTimeout * 1000));
        } catch (IOException e) {
            closeQuietly(raw);
            throw new ConnectionException("Cannot connect to " + config.host + ":" + config.port + " - " + e.getMessage(), e);
        }
        try {
            SSLContext ctx = buildContext();
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, config.host, config.port, true);
            s.setUseClientMode(true);
            s.setEnabledProtocols(protocolsAtLeastTls12(s));
            if (config.verifyPeer && config.verifyPeerName) {
                SSLParameters p = s.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS"); // verify the server name against its certificate
                s.setSSLParameters(p);
            }
            s.setSoTimeout((int) Math.max(1000, config.readTimeout * 1000));
            s.startHandshake();
            this.sock = s;
            this.out = s.getOutputStream();
            this.in = new DataInputStream(s.getInputStream());
        } catch (Exception e) {
            closeQuietly(raw);
            throw new ConnectionException("TLS handshake with " + config.host + ":" + config.port + " failed - " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isOpen() {
        return sock != null && fatal == null;
    }

    @Override
    public void writeFrame(String xml) {
        OutputStream o = usableOut();
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        int total = body.length + 4;
        byte[] frame = new byte[total];
        frame[0] = (byte) (total >>> 24);
        frame[1] = (byte) (total >>> 16);
        frame[2] = (byte) (total >>> 8);
        frame[3] = (byte) total;
        System.arraycopy(body, 0, frame, 4, body.length);
        try {
            o.write(frame);
            o.flush();
        } catch (IOException e) {
            throw die("Write failed (connection closed?): " + e.getMessage());
        }
    }

    @Override
    public String readFrame() {
        byte[] header = readBytes(4);
        long length = ((long) (header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < 4 || length > MAX_FRAME) {
            // The stream is no longer aligned on a frame boundary; nothing after this can be trusted.
            throw die("Invalid EPP frame length: " + length);
        }
        return new String(readBytes((int) (length - 4)), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (sock != null) {
            closeQuietly(sock);
            sock = null;
        }
    }

    // --- TLS -------------------------------------------------------------------------------------------------

    private SSLContext buildContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        TrustManager[] tms = trustManagers();
        KeyManager[] kms = keyManagers();
        ctx.init(kms, tms, null);
        return ctx;
    }

    private TrustManager[] trustManagers() throws Exception {
        if (!config.verifyPeer) {
            // The documented, explicit opt-out. Verifies nothing - only for a test endpoint.
            return new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (config.caFile != null) {
            // A private-CA or self-signed endpoint: trust exactly the certificates in this PEM bundle.
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : readCertificates(config.caFile)) {
                ks.setCertificateEntry("ca-" + (i++), c);
            }
            tmf.init(ks);
        } else {
            tmf.init((KeyStore) null); // the system trust store, which is what the default means
        }
        return tmf.getTrustManagers();
    }

    private KeyManager[] keyManagers() throws Exception {
        if (config.clientCert == null) {
            return null; // no mutual TLS
        }
        List<Certificate> chain = readCertificates(config.clientCert);
        String keyPem = config.clientKey != null ? config.clientKey : config.clientCert;
        PrivateKey key = readPrivateKey(keyPem, config.clientKeyPassphrase);
        char[] pass = new char[0];
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("client", key, pass, chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        return kmf.getKeyManagers();
    }

    private static String[] protocolsAtLeastTls12(SSLSocket s) {
        // EPP runs over modern TLS; refuse anything below 1.2.
        List<String> keep = new ArrayList<>();
        for (String p : s.getSupportedProtocols()) {
            if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) {
                keep.add(p);
            }
        }
        return keep.toArray(new String[0]);
    }

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    private static List<Certificate> readCertificates(String path) throws Exception {
        byte[] pem = Files.readAllBytes(Paths.get(path));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> out = new ArrayList<>();
        Matcher m = PEM.matcher(new String(pem, StandardCharsets.US_ASCII));
        boolean any = false;
        while (m.find()) {
            if (!m.group(1).contains("CERTIFICATE")) {
                continue;
            }
            any = true;
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            out.add(cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        if (!any) {
            // Not PEM: try DER directly.
            for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(pem))) {
                out.add(c);
            }
        }
        return out;
    }

    private static PrivateKey readPrivateKey(String path, String passphrase) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII);
        Matcher m = PEM.matcher(text);
        while (m.find()) {
            String type = m.group(1);
            if (!type.contains("PRIVATE KEY")) {
                continue;
            }
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            PKCS8EncodedKeySpec spec;
            if (type.contains("ENCRYPTED")) {
                if (passphrase == null) {
                    throw new ConfigException("client key is encrypted but no clientKeyPassphrase was given");
                }
                EncryptedPrivateKeyInfo epki = new EncryptedPrivateKeyInfo(der);
                SecretKeyFactory skf = SecretKeyFactory.getInstance(epki.getAlgName());
                spec = epki.getKeySpec(skf.generateSecret(new PBEKeySpec(passphrase.toCharArray())));
            } else {
                spec = new PKCS8EncodedKeySpec(der); // "BEGIN PRIVATE KEY" - PKCS#8, RSA or EC
            }
            for (String algo : new String[]{"RSA", "EC"}) {
                try {
                    return KeyFactory.getInstance(algo).generatePrivate(spec);
                } catch (Exception ignore) {
                    // try the next algorithm
                }
            }
            throw new ConfigException("unsupported client key type (expected a PKCS#8 RSA or EC key)");
        }
        throw new ConfigException("no PRIVATE KEY block found in " + path);
    }

    // --- framing helpers -------------------------------------------------------------------------------------

    private OutputStream usableOut() {
        if (fatal != null) {
            throw new ConnectionException("Connection is no longer usable: " + fatal);
        }
        if (out == null) {
            throw new ConnectionException("Not connected");
        }
        return out;
    }

    private byte[] readBytes(int n) {
        if (fatal != null) {
            throw new ConnectionException("Connection is no longer usable: " + fatal);
        }
        if (in == null) {
            throw new ConnectionException("Not connected");
        }
        byte[] buf = new byte[n];
        try {
            in.readFully(buf);
        } catch (EOFException e) {
            throw die("Connection closed while reading");
        } catch (java.net.SocketTimeoutException e) {
            throw die("Read timed out");
        } catch (IOException e) {
            throw die("Connection error while reading: " + e.getMessage());
        }
        return buf;
    }

    private ConnectionException die(String reason) {
        if (fatal == null) {
            fatal = reason;
        }
        close();
        return new ConnectionException(reason);
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) {
                s.close();
            }
        } catch (IOException ignore) {
            // nothing useful to do
        }
    }
}
