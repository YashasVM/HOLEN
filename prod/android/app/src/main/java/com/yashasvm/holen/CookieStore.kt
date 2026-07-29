package com.yashasvm.holen

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CookieStore(context: Context) {
    private val cookieFile = File(context.noBackupFilesDir, "auth/cookies.txt")

    fun validFile(): File? = cookieFile.takeIf {
        it.isFile && runCatching { validateCookieBytes(it.readBytes()) }.getOrDefault(false)
    }

    fun validateExisting(): Boolean {
        if (!cookieFile.exists()) return false
        if (runCatching { validFile() != null }.getOrDefault(false)) return true
        cookieFile.delete()
        return false
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun save(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { ERROR_TOO_LARGE }
        require(validateCookieBytes(bytes)) { ERROR_INVALID }
        val parent = cookieFile.parentFile ?: throw IOException(ERROR_SAVE)
        if (!parent.exists() && !parent.mkdirs()) throw IOException(ERROR_SAVE)
        val temporary = File.createTempFile("cookies-", ".tmp", parent)
        try {
            temporary.writeBytes(bytes)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    cookieFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse { throw IOException(ERROR_SAVE) }
        } finally {
            temporary.delete()
        }
    }

    fun clear(): Boolean = !cookieFile.exists() || cookieFile.delete()

    internal fun cookieArguments(): List<String> =
        cookieArguments(validFile())

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val ERROR_INVALID = "This does not look like a Netscape cookies.txt file."
        const val ERROR_TOO_LARGE = "The cookies file is too large."
        const val ERROR_SAVE = "Cookies could not be saved."

        internal fun cookieArguments(file: File?): List<String> =
            file?.let { listOf("--cookies", it.absolutePath) }.orEmpty()

        internal fun validateCookieBytes(bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
            val text = runCatching {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() ?: return false
            if (text.any { it == '\u0000' || (it < ' ' && it != '\t' && it != '\r' && it != '\n') }) {
                return false
            }
            val lines = text.lineSequence().toList()
            val first = lines.firstOrNull()?.removePrefix("\uFEFF")?.trimEnd() ?: return false
            if (first != "# Netscape HTTP Cookie File" && first != "# HTTP Cookie File") return false
            var dataLines = 0
            for (rawLine in lines.drop(1)) {
                val line = rawLine.trimEnd('\r')
                if (line.isBlank() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue
                val fields = line.split('\t')
                if (fields.size != 7 || fields[0].isBlank() || fields[2].isBlank() || fields[5].isBlank()) {
                    return false
                }
                if (fields[1] !in setOf("TRUE", "FALSE") || fields[3] !in setOf("TRUE", "FALSE")) {
                    return false
                }
                if (fields[4].toLongOrNull() == null) return false
                dataLines++
            }
            return dataLines > 0
        }
    }
}
