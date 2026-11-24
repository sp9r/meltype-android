package io.github.sp9r.meltype.core

import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ClipboardImageEntry(
    val id: String,
    val fileName: String,
    val thumbFileName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val addedAtMs: Long,
    val sha256: String
)

/**
 * Persists copied clipboard images and thumbnails inside app cache so they can be
 * committed via FileProvider + InputConnectionCompat.commitContent without holding
 * external clipboard URI permissions indefinitely.
 */
class ClipboardImageStore(
    private val dir: File,
    private val maxItems: Int = MAX_ENTRIES
) {
    private val indexFile = File(dir, "index.tsv")

    @Synchronized
    fun list(): List<ClipboardImageEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            indexFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.split('\t')
                if (p.size < 8) return@mapNotNull null
                val id = p[0]
                val fileName = p[1]
                val thumbFileName = p[2]
                val mimeType = p[3]
                val width = p[4].toIntOrNull() ?: 0
                val height = p[5].toIntOrNull() ?: 0
                val addedAtMs = p[6].toLongOrNull() ?: 0L
                val sha256 = p[7]
                if (id.isEmpty() || fileName.isEmpty() || mimeType.isEmpty() || sha256.isEmpty()) {
                    return@mapNotNull null
                }
                if (!File(dir, fileName).isFile) return@mapNotNull null
                ClipboardImageEntry(
                    id = id,
                    fileName = fileName,
                    thumbFileName = thumbFileName,
                    mimeType = mimeType,
                    width = width,
                    height = height,
                    addedAtMs = addedAtMs,
                    sha256 = sha256
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun importBytes(
        bytes: ByteArray,
        rawMimeType: String,
        width: Int = 0,
        height: Int = 0,
        thumbBytes: ByteArray? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ClipboardImageEntry? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val mime = normalizeMimeType(rawMimeType) ?: return null
        if (!dir.exists()) dir.mkdirs()

        val hash = sha256Hex(bytes)
        val current = list().toMutableList()
        val existingIdx = current.indexOfFirst { it.sha256 == hash }
        if (existingIdx >= 0) {
            val existing = current.removeAt(existingIdx)
            val updated = existing.copy(
                addedAtMs = nowMs,
                width = if (width > 0) width else existing.width,
                height = if (height > 0) height else existing.height
            )
            if (thumbBytes != null && thumbBytes.isNotEmpty() && existing.thumbFileName.isNotEmpty()) {
                runCatching { File(dir, existing.thumbFileName).writeBytes(thumbBytes) }
            }
            current.add(0, updated)
            saveIndex(current)
            return updated
        }

        val ext = extensionForMime(mime)
        val id = "${nowMs}_${hash.take(10)}"
        val fileName = "img_${id}.$ext"
        val thumbName = "thumb_${id}.jpg"
        File(dir, fileName).writeBytes(bytes)
        val finalThumbName = if (thumbBytes != null && thumbBytes.isNotEmpty()) {
            File(dir, thumbName).writeBytes(thumbBytes)
            thumbName
        } else {
            ""
        }
        val entry = ClipboardImageEntry(
            id = id,
            fileName = fileName,
            thumbFileName = finalThumbName,
            mimeType = mime,
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            addedAtMs = nowMs,
            sha256 = hash
        )
        current.add(0, entry)
        while (current.size > maxItems) {
            val removed = current.removeAt(current.lastIndex)
            deleteFiles(removed)
        }
        saveIndex(current)
        return entry
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val removed = current.removeAt(idx)
        deleteFiles(removed)
        saveIndex(current)
        return true
    }

    fun resolveFile(entry: ClipboardImageEntry): File = File(dir, entry.fileName)

    fun resolveThumbFile(entry: ClipboardImageEntry): File? {
        if (entry.thumbFileName.isEmpty()) return null
        val f = File(dir, entry.thumbFileName)
        return if (f.isFile) f else null
    }

    private fun deleteFiles(entry: ClipboardImageEntry) {
        runCatching { File(dir, entry.fileName).delete() }
        if (entry.thumbFileName.isNotEmpty()) {
            runCatching { File(dir, entry.thumbFileName).delete() }
        }
    }

    private fun saveIndex(entries: List<ClipboardImageEntry>) {
        if (!dir.exists()) dir.mkdirs()
        val content = buildString {
            for (e in entries) {
                append(e.id.replace('\t', '_')).append('\t')
                append(e.fileName.replace('\t', '_')).append('\t')
                append(e.thumbFileName.replace('\t', '_')).append('\t')
                append(e.mimeType.replace('\t', '_')).append('\t')
                append(e.width).append('\t')
                append(e.height).append('\t')
                append(e.addedAtMs).append('\t')
                append(e.sha256).append('\n')
            }
        }
        val tmp = File(dir, "index.tsv.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_ENTRIES = 10
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        fun normalizeMimeType(rawMime: String?): String? {
            val clean = rawMime?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            if (!clean.startsWith("image/")) return null
            return when (clean) {
                "image/jpg" -> "image/jpeg"
                "image/png", "image/jpeg", "image/webp", "image/gif" -> clean
                else -> "image/png"
            }
        }

        fun extensionForMime(mime: String): String = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }

        fun selectCompatibleMime(entryMime: String, acceptedMimes: Array<String>?): String? {
            if (acceptedMimes == null || acceptedMimes.isEmpty()) return null
            val normEntry = normalizeMimeType(entryMime) ?: "image/png"
            val normAccepted = acceptedMimes
                .mapNotNull { it.substringBefore(';').trim().lowercase(Locale.US).takeIf { m -> m.isNotEmpty() } }
            if (normAccepted.isEmpty()) return null
            if (normEntry in normAccepted) return normEntry
            if ("image/*" in normAccepted || "*/*" in normAccepted) return normEntry
            if ("image/png" in normAccepted) return "image/png"
            if ("image/jpeg" in normAccepted || "image/jpg" in normAccepted) return "image/jpeg"
            if ("image/webp" in normAccepted) return "image/webp"
            return null
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        }
    }
}
