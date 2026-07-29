package com.yashasvm.holen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

class OutputStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(
        HolenStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    val treeUri: Uri?
        get() = preferences.getString(HolenStore.PREF_DOWNLOAD_TREE, null)?.let(Uri::parse)

    fun persistTree(uri: Uri, flags: Int) {
        val grantFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        context.contentResolver.takePersistableUriPermission(uri, grantFlags)
        preferences.edit { putString(HolenStore.PREF_DOWNLOAD_TREE, uri.toString()) }
    }

    fun hasValidTreeGrant(): Boolean {
        val uri = treeUri ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun stagingDirectory(jobId: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, "downloads"), jobId).apply { mkdirs() }
    }

    suspend fun publish(
        staged: StagedDownload,
        isCancelled: () -> Boolean = { false },
    ): PublishedFile = withContext(Dispatchers.IO) {
        val tree = treeUri ?: throw StorageException("Download folder permission is missing.")
        if (!hasValidTreeGrant()) throw StorageException("Download folder permission was revoked.")
        val resolver = context.contentResolver
        val treeDocument = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val existing = childNames(tree)
        val safeName = destinationName(sanitizeFileName(staged.fileName), existing)
        val document = DocumentsContract.createDocument(
            resolver,
            treeDocument,
            staged.mimeType,
            safeName,
        ) ?: throw StorageException("The selected folder could not create a file.")

        try {
            val copied = resolver.openOutputStream(document, "w")?.use { output ->
                FileInputStream(staged.file).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        if (isCancelled()) {
                            throw kotlinx.coroutines.CancellationException("Finalization cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        count += read
                    }
                    output.flush()
                    count
                }
            } ?: throw StorageException("The selected folder could not be written.")
            if (copied != staged.file.length()) {
                throw StorageException("The copied file did not match the completed download.")
            }
            staged.file.parentFile?.deleteRecursively()
            PublishedFile(document, safeName, staged.mimeType, copied)
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw error
        }
    }

    suspend fun deleteDocument(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }

    fun openIntent(job: DownloadJob): Intent? {
        val uri = job.outputUri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, job.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(job: DownloadJob): Intent? {
        val uri = job.outputUri?.let(Uri::parse) ?: return null
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = job.mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share ${job.fileName ?: job.title}",
        )
    }

    suspend fun cleanOrphanStaging(now: Long = System.currentTimeMillis()) {
        val knownJobIds = HolenStore.get(context).knownJobIds()
        withContext(Dispatchers.IO) {
            val root = stagingDirectory("_probe").parentFile ?: return@withContext
            File(root, "_probe").delete()
            root.listFiles()
                ?.filter {
                    it.isDirectory &&
                        it.name !in knownJobIds &&
                        now - it.lastModified() > ORPHAN_MAX_AGE_MS
                }
                ?.forEach(File::deleteRecursively)
        }
    }

    fun clearStaging(jobId: String) {
        stagingDirectory(jobId).deleteRecursively()
    }

    private fun childNames(tree: Uri): Set<String> {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return resolver.query(
            children,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.orEmpty()
    }

    data class PublishedFile(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
    )

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val ORPHAN_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun destinationName(requested: String, existing: Set<String>): String {
            if (requested !in existing) return requested
            val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
            val base = requested.substring(0, dot)
            val extension = requested.substring(dot)
            var index = 1
            while ("$base ($index)$extension" in existing) index++
            return "$base ($index)$extension"
        }

        fun mimeTypeFor(fileName: String, fallback: String? = null): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: fallback
                ?: "application/octet-stream"
        }
    }
}
