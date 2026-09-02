package com.yashasvm.holen

import android.content.Context
import androidx.core.content.edit

/**
 * Small durable sidecar for per-job extractor identity.
 *
 * Keeping this outside the queue schema avoids a database migration for a single recovery bit,
 * while still surviving service/process restarts. Absence means CONFIGURED, preserving behavior
 * for every existing job. Callers should only write WITHOUT_COOKIES after the explicit recovery
 * eligibility check succeeds.
 */
class JobAuthenticationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun policy(jobId: String): JobAuthenticationPolicy {
        val key = preferenceKey(jobId)
        return decodePolicy(preferences.getString(key, null))
    }

    fun set(jobId: String, policy: JobAuthenticationPolicy) {
        val key = preferenceKey(jobId)
        preferences.edit(commit = true) {
            if (policy == JobAuthenticationPolicy.CONFIGURED) {
                remove(key)
            } else {
                putString(key, policy.name)
            }
        }
    }

    fun clear(jobId: String) {
        preferences.edit(commit = true) { remove(preferenceKey(jobId)) }
    }

    /** Remove policy entries for jobs no longer present in the queue/history database. */
    fun prune(knownJobIds: Set<String>) {
        val staleKeys = preferences.all.keys.filter { key ->
            key.startsWith(KEY_PREFIX) && key.removePrefix(KEY_PREFIX) !in knownJobIds
        }
        if (staleKeys.isEmpty()) return
        preferences.edit(commit = true) { staleKeys.forEach(::remove) }
    }

    companion object {
        private const val PREFERENCES_NAME = "holen_job_authentication"
        private const val KEY_PREFIX = "job:"
        private const val MAX_JOB_ID_LENGTH = 128

        internal fun preferenceKey(jobId: String): String {
            require(jobId.isNotBlank() && jobId.length <= MAX_JOB_ID_LENGTH) { "Invalid job ID." }
            return KEY_PREFIX + jobId
        }

        internal fun decodePolicy(raw: String?): JobAuthenticationPolicy =
            runCatching { raw?.let(JobAuthenticationPolicy::valueOf) }
                .getOrNull()
                ?: JobAuthenticationPolicy.CONFIGURED
    }
}
