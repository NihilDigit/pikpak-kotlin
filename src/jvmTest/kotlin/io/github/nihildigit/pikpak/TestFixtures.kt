package io.github.nihildigit.pikpak

/** Inputs the live tests share. */
internal object TestFixtures {
    /**
     * A freely redistributable release large enough to time reads against.
     * PikPak keeps popular torrents cached, so submitting it usually completes
     * without a real fetch.
     */
    const val ARCH_ISO_MAGNET =
        "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=archlinux-2026.04.01-x86_64.iso"
}
