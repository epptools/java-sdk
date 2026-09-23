package com.epptools.sdk;

/**
 * Single source of the library version. It goes on the wire in one place: the {@code <loginSec:app>} element
 * of a login, when the Login Security extension is in use (RFC 8807), so that a registry reading its own logs
 * can tell which build produced a frame.
 */
public final class Version {
    private Version() {}

    /** The library version. The build files declare the same number, and a release check keeps them in step. */
    public static final String VERSION = "1.1.1";
}
