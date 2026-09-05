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

/**
 * Limits the explicit no-cookie recovery action to failures which already tell the user that
 * cookie isolation is a reasonable diagnostic. Account-gated and age-gated failures deliberately
 * never qualify, and direct-file HTTP failures must not be conflated with yt-dlp authentication.
 */
fun shouldOfferCookieIsolationRetry(
    sourceKind: SourceKind,
    status: JobStatus,
    errorMessage: String?,
    cookiesConfigured: Boolean,
): Boolean {
    if (!cookiesConfigured || sourceKind != SourceKind.MEDIA || status != JobStatus.FAILED) return false
    val failure = errorMessage?.lowercase().orEmpty()
    if (!failure.contains("retry once without cookies")) return false
    return !failure.contains("signed-in account") &&
        !failure.contains("age verification") &&
        !failure.contains("members-only") &&
        !failure.contains("members only")
}

fun friendlyFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    val normalized = message.lowercase()
    val extractorHttpFailure = extractorHttpFailure(normalized)
    return when {
        normalized.contains("drm") -> "This source is DRM-protected and cannot be downloaded."
        normalized.contains("media engine reset is pending") ||
            normalized.contains("close and reopen holen") ->
            "The media engine was reset. Close and reopen HOLEN before analyzing or downloading media."
        isStaleCookieFailure(normalized) ->
            "The saved account cookies are no longer valid. Export fresh cookies from a signed-in browser session, replace them in Settings, then retry."
        normalized.contains("confirm you're not a bot") ||
            normalized.contains("confirm you’re not a bot") ||
            normalized.contains("verify you are human") ||
            normalized.contains("unusual traffic") ->
            "The source asked for a bot check. Wait a little, then retry; valid cookies may help for content you can access."
        isRateLimitFailure(normalized) ->
            "The source is rate-limiting downloads. Wait before retrying; repeated retries can extend the limit."
        isAgeRestrictedFailure(normalized) ->
            "This video needs age verification. Use fresh cookies from an account permitted to watch it, then retry."
        isLoginRequiredFailure(normalized) ->
            "This source needs a signed-in account. Add fresh cookies from an account permitted to access it, then retry."
        isRequestedFormatUnavailableFailure(normalized) ->
            "That format is no longer available from the source. Re-analyze the link and choose an available format. If this is public media and cookies are configured, retry once without cookies before changing quality; update the media engine if the expected quality is still missing."
        isRegionRestrictedFailure(normalized) ->
            "This media is not available from the source in your current region."
        isUnavailableMediaFailure(normalized) ->
            "This media is currently unavailable from the source. Re-analyze the link; if it still plays normally, update the media engine and retry."
        extractorHttpFailure != null -> extractorHttpFailure
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
        isPostProcessingFailure(normalized) ->
            "Media post-processing failed while merging or converting the download. Check free storage, then update or reset the media engine and retry."
        isFragmentTransferFailure(normalized) ->
            "One or more media fragments could not be downloaded completely. Re-analyze the link and retry; if it persists, update the media engine before changing quality."
        message.contains("timed out", true) ||
            message.contains("timeout", true) -> "The network timed out. Retry to continue the partial download."
        message.contains("media engine startup failed", true) ||
            message.contains("engine failed to initialize", true) ||
            message.contains("could not initialize youtubedl", true) ||
            message.contains("dlopen failed", true) ||
            message.contains("libpython", true) ->
            "The media engine could not start. Reset or update it in Settings."
        isTransientNetworkFailure(normalized) ||
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
    return httpFailure(status, directFile = true)
}

private fun extractorHttpFailure(message: String): String? {
    val status = Regex("""http error (\d{3})""")
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: return null
    return httpFailure(status, directFile = false)
}

private fun httpFailure(status: Int, directFile: Boolean): String = when (status) {
    401, 403 -> if (directFile) {
        "The server denied access (HTTP $status). Check that the link is still valid and that you have access to the file."
    } else {
        "The source denied access (HTTP $status). If this is public media and cookies are configured, retry once without cookies; otherwise refresh the cookies/account access."
    }
    404, 410 -> if (directFile) {
        "The file is no longer available (HTTP $status). Check the link or get a fresh download URL."
    } else {
        "The media is no longer available (HTTP $status), or the source changed its URL. Refresh the link and retry."
    }
    402, 429 -> "The source is rate-limiting downloads (HTTP $status). Wait before retrying; repeated retries can extend the limit."
    in 500..599 -> "The source is temporarily unavailable (HTTP $status). Retry later."
    else -> "The source returned HTTP $status. Check the link and try again."
}

private fun isRateLimitFailure(message: String): Boolean = listOf(
    "too many requests",
    "rate limit exceeded",
    "rate-limit exceeded",
    "rate limited",
    "rate-limited",
).any(message::contains)

private fun isStaleCookieFailure(message: String): Boolean = listOf(
    "account cookies are no longer valid",
    "cookies are no longer valid",
    "cookies have likely been rotated",
    "cookies were rotated",
).any(message::contains)

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

private fun isRequestedFormatUnavailableFailure(message: String): Boolean = listOf(
    "requested format is not available",
    "requested format not available",
    "requested quality is not available",
).any(message::contains)

private fun isRegionRestrictedFailure(message: String): Boolean = listOf(
    "not available in your country",
    "not available in your region",
    "geo-restricted",
    "georestricted",
).any(message::contains)

private fun isUnavailableMediaFailure(message: String): Boolean = listOf(
    "video unavailable",
    "this video is unavailable",
    "this content isn't available",
    "this content is not available",
    "media unavailable",
).any(message::contains)

private fun isPostProcessingFailure(message: String): Boolean = listOf(
    "postprocessing:",
    "post-processing",
    "post processing",
    "ffmpeg exited with code",
    "ffmpeg error",
    "conversion failed",
).any(message::contains)

private fun isFragmentTransferFailure(message: String): Boolean = listOf(
    "fragment not found",
    "downloaded file is empty",
    "unable to download video data",
).any(message::contains)

private fun isTransientNetworkFailure(message: String): Boolean = listOf(
    "connection reset",
    "connection aborted",
    "connection refused",
    "remote end closed connection",
    "broken pipe",
    "temporary failure in name resolution",
    "name or service not known",
    "network is unreachable",
    "unable to download webpage",
    "unable to download api page",
    "ssl eof",
).any(message::contains)
