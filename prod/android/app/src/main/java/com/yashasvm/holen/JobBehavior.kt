package com.yashasvm.holen

fun JobStatus.canTransitionTo(next: JobStatus): Boolean = when (this) {
    JobStatus.QUEUED -> next in setOf(JobStatus.RUNNING, JobStatus.CANCELLED)
    JobStatus.RUNNING -> next in setOf(
        JobStatus.QUEUED,
        JobStatus.FINALIZING,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
    )
    JobStatus.FINALIZING -> next in setOf(
        JobStatus.QUEUED,
        JobStatus.COMPLETED,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
    )
    JobStatus.FAILED, JobStatus.CANCELLED -> next == JobStatus.QUEUED
    JobStatus.COMPLETED -> false
}

fun friendlyFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    val normalized = message.lowercase()
    return when {
        normalized.contains("drm") -> "This source is DRM-protected and cannot be downloaded."
        normalized.contains("confirm you're not a bot") ||
            normalized.contains("confirm you’re not a bot") ||
            normalized.contains("verify you are human") ||
            normalized.contains("unusual traffic") ||
            normalized.contains("http error 429") ->
            "The source asked for a bot check. Wait a little, then retry; valid cookies may help for content you can access."
        isAgeRestrictedFailure(normalized) ->
            "This video needs age verification. Use fresh cookies from an account permitted to watch it, then retry."
        isLoginRequiredFailure(normalized) ->
            "This source needs a signed-in account. Add fresh cookies from an account permitted to access it, then retry."
        normalized.contains("unsupported") -> "This URL is not supported by the current engine."
        message.startsWith("Network response ", true) -> directHttpFailure(message)
        message.contains("space", true) ||
            message.contains("ENOSPC", true) -> "There is not enough storage space."
        error is StorageException -> message.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.take(180)
            ?: "The selected download folder could not be written."
        message.contains("permission", true) ||
            message.contains("denied", true) -> "Download folder access was revoked. Choose the folder again."
        message.contains("timed out", true) ||
            message.contains("timeout", true) -> "The network timed out. Retry to continue the partial download."
        message.contains("media engine startup failed", true) ||
            message.contains("engine failed to initialize", true) ||
            message.contains("could not initialize youtubedl", true) ||
            message.contains("dlopen failed", true) ||
            message.contains("libpython", true) ->
            "The media engine could not start. Reset or update it in Settings."
        message.contains("network", true) ||
            error is java.io.IOException -> "The network transfer failed. Retry to continue the partial download."
        else -> message.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("ERROR: ")
            ?.take(180)
            ?: "The download failed. Try again."
    }
}

private fun directHttpFailure(message: String): String {
    val status = message.substringAfter("Network response ").takeWhile(Char::isDigit).toIntOrNull()
        ?: return "The server rejected the request. Check the link and try again."
    return when (status) {
        401, 403 -> "The server denied access (HTTP $status). Check that the link is still valid and that you have access to the file."
        404, 410 -> "The file is no longer available (HTTP $status). Check the link or get a fresh download URL."
        429 -> "The server is rate-limiting downloads (HTTP 429). Wait a little, then retry."
        in 500..599 -> "The server is temporarily unavailable (HTTP $status). Retry later."
        else -> "The server returned HTTP $status. Check the link and try again."
    }
}

private fun isAgeRestrictedFailure(message: String): Boolean = listOf(
    "age-restricted",
    "age restricted",
    "age verification",
    "verify your age",
    "confirm your age",
    "age-confirmation",
).any(message::contains)

private fun isLoginRequiredFailure(message: String): Boolean = listOf(
    "login required",
    "sign in required",
    "sign in to confirm",
    "please sign in",
    "authentication required",
    "members-only",
    "members only",
    "this video is private",
).any(message::contains)
