# ProGuard / R8 rules for UFR-II AndroidPrint (release build, S1).
#
# AGP automatically keeps components declared in AndroidManifest.xml
# (CanonPrintService, MainActivity, AddPrinterActivity), so no explicit -keep
# is needed for them. The UI is built programmatically (no XML layout inflation),
# so there is no view/attribute reflection to preserve.

# --- Apache Commons Compress (tar + gzip rootfs extraction) ---
# RootfsManager uses TarArchiveInputStream + GzipCompressorInputStream directly,
# but commons-compress resolves compressor/archiver backends via reflection and
# references several OPTIONAL third-party codecs that are NOT on our classpath
# (we only ship gzip + tar). Keep the package and silence missing-class warnings
# so R8 neither strips the codecs we use nor fails on the ones we don't.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# Optional codec backends referenced (but unused) by commons-compress.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
