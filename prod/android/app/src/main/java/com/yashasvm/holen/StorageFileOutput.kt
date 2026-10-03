package com.yashasvm.holen

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Wraps private staging-file failures so retry logic never mistakes them for network I/O. */
internal class StorageFileOutput private constructor(
    private val output: FileOutputStream,
) : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            output.write(buffer, offset, length)
        } catch (error: IOException) {
            throw StorageException("Could not write to private download storage.", error)
        }
    }

    fun sync() {
        try {
            output.fd.sync()
        } catch (error: IOException) {
            throw StorageException("Could not sync the staged download to storage.", error)
        }
    }

    override fun close() {
        try {
            output.close()
        } catch (error: IOException) {
            throw StorageException("Could not close the staged download file.", error)
        }
    }

    companion object {
        fun open(file: File, append: Boolean): StorageFileOutput = try {
            StorageFileOutput(FileOutputStream(file, append))
        } catch (error: IOException) {
            throw StorageException("Could not open private download storage for writing.", error)
        }
    }
}
