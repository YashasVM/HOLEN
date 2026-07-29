package com.yashasvm.holen

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HolenStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, SCHEMA_VERSION) {

    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = mutableJobs.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) = migrate(db, 0, SCHEMA_VERSION)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Database downgrade from $oldVersion to $newVersion is not supported")
    }

    private fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 1 && newVersion >= 1) {
            db.execSQL(
                """
                CREATE TABLE jobs (
                    id TEXT PRIMARY KEY NOT NULL,
                    source_url TEXT NOT NULL,
                    source_kind TEXT NOT NULL,
                    format TEXT NOT NULL,
                    title TEXT NOT NULL,
                    thumbnail_url TEXT,
                    status TEXT NOT NULL,
                    progress INTEGER NOT NULL DEFAULT 0,
                    bytes_downloaded INTEGER,
                    total_bytes INTEGER,
                    speed_bytes_per_second INTEGER,
                    eta_seconds INTEGER,
                    output_uri TEXT,
                    file_name TEXT,
                    mime_type TEXT,
                    error_message TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX jobs_status_created ON jobs(status, created_at)")
            version = 1
        }
        check(version == newVersion) {
            "Missing forward migration from database $oldVersion to $newVersion"
        }
    }

    suspend fun insert(jobs: List<DownloadJob>) = write {
        beginTransaction()
        try {
            jobs.forEach { insertOrThrow("jobs", null, it.toValues()) }
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    suspend fun get(id: String): DownloadJob? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "jobs",
            COLUMNS,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }
    }

    suspend fun claimNextQueued(): DownloadJob? = write {
        beginTransaction()
        try {
            val job = query(
                "jobs",
                COLUMNS,
                "status = ?",
                arrayOf(JobStatus.QUEUED.name),
                null,
                null,
                "created_at ASC",
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }
                ?: return@write null
            val now = System.currentTimeMillis()
            val claimed = update(
                "jobs",
                ContentValues().apply {
                    put("status", JobStatus.RUNNING.name)
                    putNull("error_message")
                    put("updated_at", now)
                },
                "id = ? AND status = ?",
                arrayOf(job.id, JobStatus.QUEUED.name),
            )
            if (claimed != 1) return@write null
            setTransactionSuccessful()
            job.copy(
                status = JobStatus.RUNNING,
                errorMessage = null,
                updatedAt = now,
            )
        } finally {
            endTransaction()
        }
    }

    suspend fun hasQueued(): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT 1 FROM jobs WHERE status = ? LIMIT 1",
            arrayOf(JobStatus.QUEUED.name),
        ).use(Cursor::moveToFirst)
    }

    suspend fun knownJobIds(): Set<String> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "jobs",
            arrayOf("id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    suspend fun transition(
        id: String,
        status: JobStatus,
        errorMessage: String? = null,
        resetProgress: Boolean = false,
    ): Boolean = write {
        val current = query(
            "jobs",
            arrayOf("status"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) JobStatus.valueOf(cursor.getString(0)) else null
        } ?: return@write false
        require(current == status || current.canTransitionTo(status)) {
            "Invalid job state transition: $current to $status"
        }
        update(
            "jobs",
            ContentValues().apply {
                put("status", status.name)
                put("updated_at", System.currentTimeMillis())
                if (errorMessage == null) putNull("error_message") else put("error_message", errorMessage)
                if (resetProgress) {
                    put("progress", 0)
                    putNull("bytes_downloaded")
                    putNull("total_bytes")
                    putNull("speed_bytes_per_second")
                    putNull("eta_seconds")
                }
            },
            "id = ? AND status = ?",
            arrayOf(id, current.name),
        ) == 1
    }

    suspend fun cancelIfQueued(id: String): Boolean = write {
        update(
            "jobs",
            ContentValues().apply {
                put("status", JobStatus.CANCELLED.name)
                putNull("error_message")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status = ?",
            arrayOf(id, JobStatus.QUEUED.name),
        ) == 1
    }

    suspend fun cancelActive(id: String): Boolean = write {
        update(
            "jobs",
            ContentValues().apply {
                put("status", JobStatus.CANCELLED.name)
                putNull("error_message")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status IN (?, ?, ?)",
            arrayOf(
                id,
                JobStatus.QUEUED.name,
                JobStatus.RUNNING.name,
                JobStatus.FINALIZING.name,
            ),
        ) == 1
    }

    suspend fun updateProgress(id: String, progress: TransferProgress): Boolean {
        val updatedAt = System.currentTimeMillis()
        return write(refresh = false) {
        update(
            "jobs",
            ContentValues().apply {
                put("progress", progress.percent)
                progress.bytesDownloaded.putOrNull(this, "bytes_downloaded")
                progress.totalBytes.putOrNull(this, "total_bytes")
                progress.speedBytesPerSecond.putOrNull(this, "speed_bytes_per_second")
                progress.etaSeconds.putOrNull(this, "eta_seconds")
                put("updated_at", updatedAt)
            },
            "id = ? AND status = ?",
            arrayOf(id, JobStatus.RUNNING.name),
        ) == 1
        }.also { updated ->
            if (updated) {
                mutableJobs.value = mutableJobs.value.map { job ->
                    if (job.id == id) job.copy(
                        progress = progress.percent,
                        bytesDownloaded = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                        speedBytesPerSecond = progress.speedBytesPerSecond,
                        etaSeconds = progress.etaSeconds,
                        updatedAt = updatedAt,
                    ) else job
                }
            }
        }
    }

    suspend fun complete(
        id: String,
        outputUri: String,
        fileName: String,
        mimeType: String,
        byteCount: Long,
    ) = write {
        update(
            "jobs",
            ContentValues().apply {
                put("status", JobStatus.COMPLETED.name)
                put("progress", 100)
                put("bytes_downloaded", byteCount)
                put("total_bytes", byteCount)
                putNull("speed_bytes_per_second")
                put("eta_seconds", 0)
                put("output_uri", outputUri)
                put("file_name", fileName)
                put("mime_type", mimeType)
                putNull("error_message")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status = ?",
            arrayOf(id, JobStatus.FINALIZING.name),
        )
    }

    suspend fun requeueInterrupted() = write {
        update(
            "jobs",
            ContentValues().apply {
                put("status", JobStatus.QUEUED.name)
                put("progress", 0)
                putNull("speed_bytes_per_second")
                putNull("eta_seconds")
                put("error_message", "Interrupted. Tap Retry to resume if it does not restart.")
                put("updated_at", System.currentTimeMillis())
            },
            "status IN (?, ?)",
            arrayOf(JobStatus.RUNNING.name, JobStatus.FINALIZING.name),
        )
    }

    suspend fun clearFinished(ids: Set<String>? = null) = write {
        val terminal = arrayOf(
            JobStatus.COMPLETED.name,
            JobStatus.FAILED.name,
            JobStatus.CANCELLED.name,
        )
        if (ids.isNullOrEmpty()) {
            delete("jobs", "status IN (?, ?, ?)", terminal)
        } else {
            ids.forEach { id ->
                delete(
                    "jobs",
                    "id = ? AND status IN (?, ?, ?)",
                    arrayOf(id, *terminal),
                )
            }
        }
    }

    suspend fun remove(id: String) = write {
        delete("jobs", "id = ?", arrayOf(id))
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutableJobs.value = readableDatabase.query(
            "jobs",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC",
            "100",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toJob())
            }
        }
    }

    private suspend fun <T> write(
        refresh: Boolean = true,
        block: SQLiteDatabase.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                writableDatabase.block().also { if (refresh) refresh() }
            }
        }

    private fun DownloadJob.toValues() = ContentValues().apply {
        put("id", id)
        put("source_url", sourceUrl)
        put("source_kind", sourceKind.name)
        put("format", format.name)
        put("title", title)
        thumbnailUrl.putOrNull(this, "thumbnail_url")
        put("status", status.name)
        put("progress", progress)
        bytesDownloaded.putOrNull(this, "bytes_downloaded")
        totalBytes.putOrNull(this, "total_bytes")
        speedBytesPerSecond.putOrNull(this, "speed_bytes_per_second")
        etaSeconds.putOrNull(this, "eta_seconds")
        outputUri.putOrNull(this, "output_uri")
        fileName.putOrNull(this, "file_name")
        mimeType.putOrNull(this, "mime_type")
        errorMessage.putOrNull(this, "error_message")
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toJob() = DownloadJob(
        id = string("id")!!,
        sourceUrl = string("source_url")!!,
        sourceKind = SourceKind.valueOf(string("source_kind")!!),
        format = DownloadFormat.valueOf(string("format")!!),
        title = string("title")!!,
        thumbnailUrl = string("thumbnail_url"),
        status = JobStatus.valueOf(string("status")!!),
        progress = int("progress"),
        bytesDownloaded = longOrNull("bytes_downloaded"),
        totalBytes = longOrNull("total_bytes"),
        speedBytesPerSecond = longOrNull("speed_bytes_per_second"),
        etaSeconds = longOrNull("eta_seconds"),
        outputUri = string("output_uri"),
        fileName = string("file_name"),
        mimeType = string("mime_type"),
        errorMessage = string("error_message"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String): String? =
        index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.longOrNull(name: String): Long? =
        index(name).let { if (isNull(it)) null else getLong(it) }

    private fun Long?.putOrNull(values: ContentValues, key: String) {
        if (this == null) values.putNull(key) else values.put(key, this)
    }

    private fun String?.putOrNull(values: ContentValues, key: String) {
        if (this == null) values.putNull(key) else values.put(key, this)
    }

    companion object {
        const val DATABASE_NAME = "holen.db"
        const val SCHEMA_VERSION = 1
        const val PREFERENCES_NAME = "holen"
        const val PREF_DOWNLOAD_TREE = "download_tree_uri"
        const val PREF_ENGINE_VERSION = "engine_version"
        const val PREF_RIGHTS_ACKNOWLEDGED = "rights_acknowledged"
        const val PREF_ONBOARDING_COMPLETED = "onboarding_completed"

        private val COLUMNS = arrayOf(
            "id",
            "source_url",
            "source_kind",
            "format",
            "title",
            "thumbnail_url",
            "status",
            "progress",
            "bytes_downloaded",
            "total_bytes",
            "speed_bytes_per_second",
            "eta_seconds",
            "output_uri",
            "file_name",
            "mime_type",
            "error_message",
            "created_at",
            "updated_at",
        )

        @Volatile
        private var instance: HolenStore? = null

        fun get(context: Context): HolenStore =
            instance ?: synchronized(this) {
                instance ?: HolenStore(context).also { instance = it }
            }
    }
}
