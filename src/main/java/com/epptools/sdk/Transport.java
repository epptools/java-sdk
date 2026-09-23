package com.epptools.sdk;

/**
 * The raw EPP-over-TLS transport: it ships and receives byte frames and knows nothing about EPP semantics.
 * {@link Connection} is the real implementation; the interface exists so a test can stand in a fake wire.
 *
 * Framing is RFC 5734: each message is prefixed with a 4-byte big-endian total length that includes the 4
 * header bytes. Lengths are the UTF-8 encoded byte count, never the character count, so Cyrillic and IDN
 * payloads are framed correctly.
 */
public interface Transport {
    /** Open the TLS connection. May be called again to reopen after a failure. */
    void open();

    /** Whether the connection is open and has not latched a fatal error. */
    boolean isOpen();

    /** Send one XML frame. */
    void writeFrame(String xml);

    /** Read one XML frame, blocking until a whole frame arrives. */
    String readFrame();

    /** Close the socket. Safe to call more than once. */
    void close();
}
