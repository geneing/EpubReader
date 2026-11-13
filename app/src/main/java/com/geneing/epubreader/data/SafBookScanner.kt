package com.geneing.epubreader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SafBookScanner(
    private val context: Context,
) {
    suspend fun scan(folderUri: Uri, folderUriString: String): List<BookEntity> {
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("This folder is no longer available. Select it again to restore access.")
        check(root.isDirectory && root.canRead()) {
            "EpubReader can't read this folder. Check its permission or select the folder again."
        }

        val results = LinkedHashMap<String, BookEntity>()
        val visitedDirectories = HashSet<String>()
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(root, relativePath = "", depth = 0))

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, directoryPath, depth) = pending.removeLast()
            if (!visitedDirectories.add(directory.uri.toString())) continue

            for (child in directory.listFiles()) {
                currentCoroutineContext().ensureActive()
                val childName = child.name ?: continue
                if (child.isDirectory) {
                    if (depth < MAX_NESTED_FOLDER_DEPTH && child.canRead()) {
                        val childPath = if (directoryPath.isEmpty()) childName else "$directoryPath/$childName"
                        pending.add(PendingDirectory(child, childPath, depth + 1))
                    }
                    continue
                }

                if (!child.isFile || !child.canRead()) continue
                val mimeType = context.contentResolver.getType(child.uri)
                    ?: BookFormat.from(childName, null)?.mimeType
                    ?: continue
                if (BookFormat.from(childName, mimeType) == null) continue

                results[child.uri.toString()] = BookEntity(
                    uri = child.uri.toString(),
                    folderUri = folderUriString,
                    displayName = childName,
                    mimeType = mimeType,
                    relativePath = if (directoryPath.isEmpty()) childName else "$directoryPath/$childName",
                    sizeBytes = child.length().coerceAtLeast(0L),
                    lastModified = child.lastModified().coerceAtLeast(0L),
                    isAvailable = true,
                )
            }
        }

        return results.values.toList()
    }

    private data class PendingDirectory(
        val directory: DocumentFile,
        val relativePath: String,
        val depth: Int,
    )

    private companion object {
        const val MAX_NESTED_FOLDER_DEPTH = 16
    }
}
