package cz.ufrii.print

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "CanonPrint"

class RootfsManager(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "rootfs")
    private val versionMarker: File = File(rootfsDir, ".rootfs_version")

    /** Read the expected version from the bundled rootfs.version asset (sha256 of rootfs.tar.gz).
     *  Falls back to "unversioned" if the asset is absent (older build). */
    private fun bundledVersion(): String {
        return try {
            context.assets.open("rootfs.version").bufferedReader().readText().trim()
        } catch (e: Exception) {
            Log.w(TAG, "[rootfs] rootfs.version asset missing, falling back to 'unversioned': ${e.message}")
            "unversioned"
        }
    }

    fun ensureExtracted(progress: (String) -> Unit): File {
        val expectedVersion = bundledVersion()
        val currentVersion = if (versionMarker.exists()) versionMarker.readText().trim() else null

        if (currentVersion != null && currentVersion == expectedVersion) {
            Log.d(TAG, "[rootfs] skip reason=already_extracted version=$expectedVersion")
            progress("rootfs již extrahován (verze $expectedVersion), přeskakuji")
            return rootfsDir
        }

        if (currentVersion != null) {
            Log.i(TAG, "[rootfs] version mismatch current=$currentVersion expected=$expectedVersion — wiping")
            progress("Nová verze rootfs ($expectedVersion), mažu starý rootfs...")
        } else {
            Log.i(TAG, "[rootfs] no version marker found, extracting fresh")
        }

        Log.i(TAG, "[rootfs] start extraction target=${rootfsDir.absolutePath}")
        progress("Extrahuji rootfs do ${rootfsDir.absolutePath} ...")

        // Wipe any previous rootfs completely before extracting
        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
            Log.d(TAG, "[rootfs] old rootfs deleted")
        }
        rootfsDir.mkdirs()

        val rootfsCanonical = rootfsDir.canonicalPath

        var fileCount = 0
        var dirCount = 0
        var symlinkCount = 0

        // Determine the actual asset name at runtime — AGP may decompress .gz assets
        // and rename them (e.g. rootfs.tar.gz → rootfs.tar), so we enumerate and match.
        val allAssets = context.assets.list("") ?: emptyArray()
        val assetName = allAssets.firstOrNull { it.startsWith("rootfs.tar") }
            ?: throw java.io.FileNotFoundException(
                "rootfs asset not found. Available assets: ${allAssets.joinToString()}"
            )
        Log.i(TAG, "[rootfs] using asset=$assetName (available: ${allAssets.joinToString()})")
        progress("Asset archivu: $assetName")

        context.assets.open(assetName).use { rawStream ->
            val buffered = BufferedInputStream(rawStream, 65536)

            // Detekovat gzip magic (1f 8b)
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()

            val isGzip = (b0 == 0x1f && b1 == 0x8b)
            Log.d(TAG, "[rootfs] asset=$assetName stream_type=${if (isGzip) "gzip" else "plain-tar"}")
            progress("Formát archivu: ${if (isGzip) "gzip" else "plain tar"}")

            val tarStream = if (isGzip) {
                TarArchiveInputStream(GzipCompressorInputStream(buffered))
            } else {
                TarArchiveInputStream(buffered)
            }

            tarStream.use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val entryName = entry.name.trimStart('/')
                    if (entryName.isEmpty() || entryName == ".") {
                        entry = tar.nextEntry
                        continue
                    }

                    val target = File(rootfsDir, entryName)

                    // Path-traversal guard
                    if (!target.canonicalPath.startsWith(rootfsCanonical)) {
                        Log.w(TAG, "[rootfs] skip path_traversal entry=${entry.name}")
                        entry = tar.nextEntry
                        continue
                    }

                    when {
                        entry.isDirectory -> {
                            target.mkdirs()
                            dirCount++
                        }
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || isSymlink(target)) {
                                target.delete()
                            }
                            try {
                                Os.symlink(entry.linkName, target.absolutePath)
                                symlinkCount++
                            } catch (e: Exception) {
                                Log.w(TAG, "[rootfs] symlink_fail entry=${entry.name} link=${entry.linkName} err=${e.message}")
                            }
                        }
                        else -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                tar.copyTo(out, bufferSize = 65536)
                            }
                            // Nastavení permissions z tar mode
                            val mode = entry.mode
                            target.setReadable(true, false)
                            target.setWritable(true, false)
                            // Executable bit (owner, group nebo others exec)
                            val isExecByMode = (mode and 0b001_000_000) != 0 ||
                                               (mode and 0b000_001_000) != 0 ||
                                               (mode and 0b000_000_001) != 0
                            // Force exec pro filtry/backends/binárky
                            val forceExec = entryName.startsWith("usr/lib/cups/filter") ||
                                            entryName.startsWith("usr/lib/cups/backend") ||
                                            entryName.startsWith("usr/bin/") ||
                                            entryName.startsWith("bin/") ||
                                            entryName.startsWith("usr/sbin/")
                            if (isExecByMode || forceExec) {
                                target.setExecutable(true, false)
                            }
                            fileCount++
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        Log.i(TAG, "[rootfs] extraction done files=$fileCount dirs=$dirCount symlinks=$symlinkCount")
        progress("Extrakce dokončena: $fileCount souborů, $dirCount adresářů, $symlinkCount symlinkůs")

        // Zajisti existenci zapisovatelných adresářů potřebných filtrem
        val writableDirs = listOf(
            "var/spool/cups/tmp",
            "var/cache/cups",
            "var/temp",
            "tmp"
        )
        for (relPath in writableDirs) {
            val dir = File(rootfsDir, relPath)
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(TAG, "[rootfs] created writable dir $relPath")
            }
            dir.setWritable(true, false)
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }

        // NOTE: /etc/ld.so.conf and /usr/lib/libjbig.so* symlinks are created by
        // build-bundle.sh and live inside rootfs.tar.gz — no runtime patching needed here.

        // Write version marker last — a crash mid-extract leaves no valid marker
        versionMarker.writeText(expectedVersion)
        Log.i(TAG, "[rootfs] extracted, version=$expectedVersion")
        progress("rootfs připraven (verze $expectedVersion)")

        return rootfsDir
    }

    /** Zjistí, zda jde o symbolický link (i pokud cíl neexistuje). */
    private fun isSymlink(file: File): Boolean {
        return try {
            file.canonicalPath != file.absolutePath
        } catch (_: Exception) {
            false
        }
    }
}
