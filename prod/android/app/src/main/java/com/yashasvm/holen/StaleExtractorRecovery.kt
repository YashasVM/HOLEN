package com.yashasvm.holen

/**
 * Identifies only high-confidence yt-dlp extractor breakage that may justify a one-time engine
 * refresh before retrying. False positives are intentionally more costly than false negatives:
 * auth, access, transport, storage, media availability, and post-processing failures must never
 * trigger updater maintenance automatically.
 */
internal fun isStaleExtractorCandidate(error: Throwable): Boolean =
    isStaleExtractorCandidate(error.message.orEmpty())

internal fun isStaleExtractorCandidate(message: String): Boolean {
    val normalized = message.lowercase()
    if (normalized.isBlank()) return false

    val excluded = listOf(
        "drm",
        "confirm you're not a bot",
        "confirm you’re not a bot",
        "verify you are human",
        "unusual traffic",
        "age-restricted",
        "age restricted",
        "age verification",
        "verify your age",
        "confirm your age",
        "login required",
        "sign in required",
        "sign in to confirm",
        "please sign in",
        "authentication required",
        "members-only",
        "members only",
        "this video is private",
        "not available in your country",
        "not available in your region",
        "geo-restricted",
        "georestricted",
        "video unavailable",
        "this video is unavailable",
        "this content isn't available",
        "this content is not available",
        "media unavailable",
        "requested format is not available",
        "requested format not available",
        "requested quality is not available",
        "unsupported url",
        "unsupported url scheme",
        "postprocessing:",
        "post-processing",
        "post processing",
        "ffmpeg exited with code",
        "ffmpeg error",
        "conversion failed",
        "fragment not found",
        "downloaded file is empty",
        "unable to download video data",
        "timed out",
        "timeout",
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
        "no space left",
        "enospc",
        "permission denied",
    ).any(normalized::contains)
    if (excluded) return false

    if (Regex("""http error \d{3}""").containsMatchIn(normalized)) return false

    // Generic "no formats" errors are not safe recovery signals: upstream uses the same text for
    // site/content/cookie combinations that an engine refresh cannot fix. Restrict candidates to
    // explicit extractor parsing failures accompanied by yt-dlp's bug-report guidance.
    if (!normalized.contains("please report this issue")) return false
    return normalized.contains("unable to extract") || normalized.contains("failed to extract")
}
