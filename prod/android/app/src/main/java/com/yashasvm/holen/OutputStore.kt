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
import org.json.JSONObject
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

    fun stagingDirectory(jobId: String): File =
        File(stagingRootDirectory(), jobId).apply { mkdirs() }

    private fun stagingRootDirectory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "downloads")
    }

    /**
     * Copies a completed private staging file into the user-selected SAF tree.
     * A small durable journal closes the crash window between creating the
     * document and recording the completed job in SQLite.
     */
    suspend fun publish(
        jobId: String,
        staged: StagedDownload,
        isCancelled: () -> Boolean = { false },
    ): PublishedFile = withContext(Dispatchers.IO) {
        val tree = treeUri ?: throw StorageException("Download folder permission is missing.")
        if (!hasValidTreeGrant()) throw StorageException("Download folder permission was revoked.")
        require(staged.file.isFile && staged.file.length() > 0) {
            "The completed staging file is missing. Retry the download."
        }
        val resolver = context.contentResolver
        val treeDocument = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val existing = childNames(tree)
        val safeName = destinationName(sanitizeFileName(staged.fileName), existing)
        val pending = PendingPublication(
            jobId = jobId,
            treeUri = tree.toString(),
            fileName = safeName,
            mimeType = staged.mimeType,
            byteCount = staged.file.length(),
            documentUri = null,
        )
        savePending(pending)
        var document: Uri? = null
        try {
            val created = DocumentsContract.createDocument(
                resolver,
                treeDocument,
                staged.mimeType,
                safeName,
            ) ?: throw StorageException("The selected folder could not create a file.")
            document = created
            savePending(pending.copy(documentUri = created.toString()))
            val copied = resolver.openOutputStream(created, "w")?.use { output ->
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
            PublishedFile(created, safeName, staged.mimeType, copied)
        } catch (error: Throwable) {
            val cleaned = document?.let { created ->
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                    .getOrDefault(false)
            } ?: true
            // Keep the journal when the provider refuses cleanup. Recovery can
            // inspect/delete the partial file after the grant becomes available.
            if (cleaned) clearPending(jobId)
            throw error
        }
    }

    /** Marks a published file as committed to the job database. */
    fun confirmPublication(jobId: String) = clearPending(jobId)

    /**
     * Recovers a file that reached the selected folder before the process died.
     * When the saved document URI was not written, the file is located by its
     * collision-free destination name inside the original SAF tree.
     */
    suspend fun recoverPublication(jobId: String): PublicationRecovery = withContext(Dispatchers.IO) {
        val pending = pendingPublications()[jobId] ?: return@withContext PublicationRecovery.NoJournal
        val located = pending.documentUri?.let { LocatedDocument.Found(Uri.parse(it)) }
            ?: findChildDocument(pending.treeUri, pending.fileName)
        val uri = when (located) {
            is LocatedDocument.Found -> located.uri
            LocatedDocument.NotFound -> return@withContext PublicationRecovery.NotCreated
            LocatedDocument.Unavailable -> return@withContext PublicationRecovery.Unavailable
        }
        when (val inspected = inspectDocument(uri)) {
            is DocumentInspection.Found -> {
                val actual = inspected.details
                when (publicationMatch(
                    pending.fileName,
                    pending.byteCount,
                    actual.fileName,
                    actual.byteCount.takeIf { it >= 0 },
                )) {
                    PublicationMatch.PARTIAL -> PublicationRecovery.Partial(uri)
                    PublicationMatch.UNAVAILABLE -> PublicationRecovery.Unavailable
                    PublicationMatch.COMPLETE -> PublicationRecovery.Complete(
                        PublishedFile(uri, actual.fileName, pending.mimeType, pending.byteCount),
                    )
                }
            }
            DocumentInspection.NotFound -> PublicationRecovery.NotCreated
            DocumentInspection.Unavailable -> PublicationRecovery.Unavailable
        }
    }

    fun pendingPublicationIds(): Set<String> = pendingPublications().keys

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
            val root = stagingRootDirectory()
            if (!root.isDirectory) return@withContext
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
        File(stagingRootDirectory(), jobId).deleteRecursively()
    }

    private fun findChildDocument(rawTree: String, fileName: String): LocatedDocument {
        val tree = Uri.parse(rawTree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return try {
            val cursor = context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: return LocatedDocument.Unavailable
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                while (it.moveToNext()) {
                    if (it.getString(nameIndex) == fileName) {
                        return LocatedDocument.Found(
                            DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(idIndex)),
                        )
                    }
                }
                LocatedDocument.NotFound
            }
        } catch (_: Throwable) {
            LocatedDocument.Unavailable
        }
    }

    private fun inspectDocument(uri: Uri): DocumentInspection {
        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            ) ?: return DocumentInspection.Unavailable
            cursor.use {
                if (!it.moveToFirst()) return DocumentInspection.NotFound
                val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndexOrThrow(OpenableColumns.SIZE)
                DocumentInspection.Found(
                    DocumentDetails(
                        fileName = it.getString(nameIndex),
                        byteCount = if (it.isNull(sizeIndex)) -1 else it.getLong(sizeIndex),
                    ),
                )
            }
        } catch (_: Throwable) {
            DocumentInspection.Unavailable
        }
    }

    private fun pendingPublications(): Map<String, PendingPublication> = synchronized(journalLock) {
        val root = runCatching {
            JSONObject(preferences.getString(PREF_PENDING_PUBLICATIONS, "{}"))
        }.getOrElse { JSONObject() }
        buildMap {
            root.keys().forEach { jobId ->
                val item = root.optJSONObject(jobId) ?: return@forEach
                val tree = item.optString("tree")
                val name = item.optString("name")
                val mime = item.optString("mime")
                val bytes = item.optLong("bytes", -1)
                if (tree.isNotBlank() && name.isNotBlank() && mime.isNotBlank() && bytes >= 0) {
                    put(
                        jobId,
                        PendingPublication(
                            jobId,
                            tree,
                            name,
                            mime,
                            bytes,
                            item.optString("uri").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }
    }

    private fun savePending(pending: PendingPublication) = synchronized(journalLock) {
        val root = runCatching {
            JSONObject(preferences.getString(PREF_PENDING_PUBLICATIONS, "{}"))
        }.getOrElse { JSONObject() }
        root.put(
            pending.jobId,
            JSONObject().apply {
                put("tree", pending.treeUri)
                put("name", pending.fileName)
                put("mime", pending.mimeType)
                put("bytes", pending.byteCount)
                pending.documentUri?.let { put("uri", it) }
            },
        )
        preferences.edit(commit = true) { putString(PREF_PENDING_PUBLICATIONS, root.toString()) }
    }

    private fun clearPending(jobId: String) = synchronized(journalLock) {
        val root = runCatching {
            JSONObject(preferences.getString(PREF_PENDING_PUBLICATIONS, "{}"))
        }.getOrElse { JSONObject() }
        if (root.has(jobId)) {
            root.remove(jobId)
            preferences.edit(commit = true) { putString(PREF_PENDING_PUBLICATIONS, root.toString()) }
        }
    }

    private fun childNames(tree: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return try {
            val cursor = context.contentResolver.query(
                children,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: throw StorageException("The selected folder could not be read.")
            cursor.use {
                val nameIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                buildSet {
                    while (it.moveToNext()) add(it.getString(nameIndex))
                }
            }
        } catch (error: StorageException) {
            throw error
        } catch (error: Throwable) {
            throw StorageException("The selected folder could not be read.", error)
        }
    }

    companion object {
        private const val PREF_PENDING_PUBLICATIONS = "pending_publications"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private val journalLock = Any()

        fun mimeTypeFor(fileName: String, fallback: String? = null): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: fallback
                ?: "application/octet-stream"
        }
    }
}

data class PublishedFile(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
)

sealed interface PublicationRecovery {
    data object NoJournal : PublicationRecovery
    data object NotCreated : PublicationRecovery
    data object Unavailable : PublicationRecovery
    data class Partial(val uri: Uri) : PublicationRecovery
    data class Complete(val file: PublishedFile) : PublicationRecovery
}

private data class PendingPublication(
    val jobId: String,
    val treeUri: String,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val documentUri: String?,
)

private sealed interface LocatedDocument {
    data class Found(val uri: Uri) : LocatedDocument
    data object NotFound : LocatedDocument
    data object Unavailable : LocatedDocument
}

private sealed interface DocumentInspection {
    data class Found(val details: DocumentDetails) : DocumentInspection
    data object NotFound : DocumentInspection
    data object Unavailable : DocumentInspection
}

private data class DocumentDetails(
    val fileName: String,
    val byteCount: Long,
)

internal fun destinationName(fileName: String, existing: Set<String>): String {
    if (fileName !in existing) return fileName
    val dot = fileName.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < fileName.lastIndex
    val base = if (hasExtension) fileName.substring(0, dot) else fileName
    val extension = if (hasExtension) fileName.substring(dot) else ""
    var suffix = 1
    while (true) {
        val candidate = "$base ($suffix)$extension"
        if (candidate !in existing) return candidate
        suffix += 1
    }
}

internal fun publicationMatch(
    expectedName: String,
    expectedBytes: Long,
    actualName: String,
    actualBytes: Long?,
): PublicationMatch = when {
    expectedName != actualName -> PublicationMatch.PARTIAL
    actualBytes == null -> PublicationMatch.UNAVAILABLE
    actualBytes != expectedBytes -> PublicationMatch.PARTIAL
    else -> PublicationMatch.COMPLETE
}

enum class PublicationMatch {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}
