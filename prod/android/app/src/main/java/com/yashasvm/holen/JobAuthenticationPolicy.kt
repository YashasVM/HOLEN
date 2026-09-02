package com.yashasvm.holen

/**
 * Authentication identity used by a persisted media job.
 *
 * CONFIGURED preserves HOLEN's current behavior and uses the device cookie store when present.
 * WITHOUT_COOKIES is an explicit per-job recovery mode for public media only; it must never be
 * selected automatically for account-gated content.
 */
enum class JobAuthenticationPolicy {
    CONFIGURED,
    WITHOUT_COOKIES,
    ;

    val usesConfiguredCookies: Boolean
        get() = this == CONFIGURED
}
