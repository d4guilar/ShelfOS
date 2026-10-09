// SPDX-License-Identifier: MPL-2.0
//
// ShelfOS Phase 3E-A native dependency foundation.
//
// This file is intentionally minimal: it proves that a ShelfOS-owned JNI
// shared library can load, link statically against the vendored libarchive
// sources, and perform a trivial, side-effect-free capability probe. It
// implements NO archive I/O: no opening archives, no entry enumeration, no
// extraction, no page/cache logic. That is out of scope for this checkpoint
// (see docs/adr/0024-native-cbr-libarchive.md) and belongs to a later
// Phase 3E slice.
//
// Safety properties maintained here:
//  - No global/mutable native state. Each call is self-contained.
//  - Every archive_read object created is freed on every path (RAII).
//  - No raw native pointers are ever returned to Kotlin.
//  - No native exception/abort can escape to the JVM: libarchive's C API
//    reports failure via return codes, which we translate deterministically.

#include <jni.h>

#include <string>

#include "archive.h"

namespace {

// RAII wrapper so archive_read_free() always runs, on every return path.
class ArchiveReadHandle {
public:
    ArchiveReadHandle() : archive_(archive_read_new()) {}

    ~ArchiveReadHandle() {
        if (archive_ != nullptr) {
            archive_read_free(archive_);
        }
    }

    ArchiveReadHandle(const ArchiveReadHandle&) = delete;
    ArchiveReadHandle& operator=(const ArchiveReadHandle&) = delete;

    [[nodiscard]] bool isValid() const { return archive_ != nullptr; }

    [[nodiscard]] archive* raw() const { return archive_; }

private:
    archive* archive_;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_d4guilar_shelfos_core_files_LibarchiveNative_nativeBackendVersion(
        JNIEnv* env, jclass /*clazz*/) {
    const char* version = archive_version_details();
    if (version == nullptr) {
        version = archive_version_string();
    }
    if (version == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(version);
}

// Capability probe: creates and frees an archive_read object, registering
// the RAR4 and RAR5 format handlers. Does not open, read, or touch any
// archive data. Returns a deterministic bitmask:
//   bit 0 (1) = archive_read object created successfully
//   bit 1 (2) = archive_read_support_format_rar() registered successfully
//   bit 2 (4) = archive_read_support_format_rar5() registered successfully
// A return of 0 means the archive_read object itself could not be created.
extern "C" JNIEXPORT jint JNICALL
Java_com_d4guilar_shelfos_core_files_LibarchiveNative_nativeProbeRarCapability(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    ArchiveReadHandle handle;
    if (!handle.isValid()) {
        return 0;
    }

    jint result = 1;  // archive_read object created.

    if (archive_read_support_format_rar(handle.raw()) == ARCHIVE_OK) {
        result |= 2;
    }
    if (archive_read_support_format_rar5(handle.raw()) == ARCHIVE_OK) {
        result |= 4;
    }

    return result;
    // handle's destructor frees the archive_read object on every path,
    // including early return above.
}
