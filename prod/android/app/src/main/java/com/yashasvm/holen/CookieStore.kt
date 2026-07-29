package com.yashasvm.holen

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CookieStore(context: Context) {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir, "auth")
    private val cookieFile = File(directory, "cookies.txt")

    fun fileOrNull(): File? = cookieFile.takeIf { it.isFile && it.length() > 0 }

    fun hasSession(): Boolean = fileOrNull() != null

    fun hasYouTubeCookies(): Boolean = fileOrNull()?.let { file ->
        runCatching { file.readText(StandardCharsets.UTF_8) }
            .getOrDefault("")
            .lineSequence()
            .map(String::trimEnd)
            .filter { it.isNotBlank() }
            .any { line ->
                val row = line.removePrefix("#HttpOnly_").split('\t')
                val domain = row.firstOrNull()?.removePrefix(".") ?: return@any false
                row.size >= 7 && (domain == "youtube.com" || domain.endsWith(".youtube.com"))
            }
    } ?: false

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (output.size() <= MAX_COOKIE_BYTES) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("The selected cookie file could not be opened.")
        require(bytes.size <= MAX_COOKIE_BYTES) { "The cookie file must be smaller than 5 MB." }
        val youtubeCookieText = youtubeCookieTextOrNull(bytes.toString(StandardCharsets.UTF_8))
            ?: throw IllegalArgumentException(
                "Choose a Netscape-format cookies.txt export that contains YouTube cookies.",
            )

        directory.mkdirs()
        val temporary = File(directory, "cookies.tmp")
        try {
            temporary.writeText(youtubeCookieText, StandardCharsets.UTF_8)
            Files.move(
                temporary.toPath(),
                cookieFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    fun remove() {
        cookieFile.delete()
    }

    companion object {
        private const val MAX_COOKIE_BYTES = 5 * 1024 * 1024

        internal fun isValidNetscapeCookieText(value: String): Boolean {
            val lines = value.removePrefix("\uFEFF").lineSequence()
                .map(String::trimEnd)
                // #HttpOnly_ is a Netscape cookie row, rather than a comment.
                .filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
                .toList()
            return lines.isNotEmpty() && lines.all { line ->
                val row = line.removePrefix("#HttpOnly_").split('\t')
                row.size >= 7 && row[0].isNotBlank() && row[5].isNotBlank()
            }
        }

        internal fun youtubeCookieTextOrNull(value: String): String? {
            val youtubeRows = value.removePrefix("\uFEFF").lineSequence()
                .map(String::trimEnd)
                .filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
                .filter { line ->
                    val row = line.removePrefix("#HttpOnly_").split('\t')
                    val domain = row.firstOrNull()?.removePrefix(".") ?: return@filter false
                    row.size >= 7 && (domain == "youtube.com" || domain.endsWith(".youtube.com"))
                }
                .toList()
            return youtubeRows
                .takeIf { it.isNotEmpty() && isValidNetscapeCookieText(it.joinToString("\n")) }
                ?.joinToString(separator = "\n", prefix = "# Netscape HTTP Cookie File\n")
        }
    }
}
