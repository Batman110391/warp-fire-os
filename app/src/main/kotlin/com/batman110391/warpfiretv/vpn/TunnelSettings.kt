package com.batman110391.warpfiretv.vpn

/**
 * What the tunnel routes, and for whom.
 *
 * The two knobs are not independent in the way they look, because Android decides VPN membership
 * per app and routing per destination:
 *
 * - [warpEnabled] `false` routes only the Cloudflare resolver addresses, for every app: DNS is
 *   encrypted, traffic and public IP are untouched.
 * - [warpEnabled] `true` routes everything. [includedApps] then restricts which apps are inside the
 *   VPN at all — apps left out get no tunnel *and* no Cloudflare DNS, since a VPN's DNS servers only
 *   apply to its members. An empty set means every app.
 */
data class TunnelSettings(
    val warpEnabled: Boolean = false,
    val includedApps: Set<String> = emptySet(),
    /**
     * Reconnect on boot. Fire OS exposes no VPN settings UI, so the OS-level always-on VPN is
     * unreachable without adb; a boot receiver reconnecting the tunnel is the distributable
     * equivalent. On by default.
     */
    val autoConnect: Boolean = true,
) {
    /** True when only a subset of apps is routed through WARP. */
    val isPerApp: Boolean get() = warpEnabled && includedApps.isNotEmpty()
}
