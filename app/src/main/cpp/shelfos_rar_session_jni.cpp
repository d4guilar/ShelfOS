// SPDX-License-Identifier: MPL-2.0
//
// ShelfOS Phase 3E-B: generic internal native RAR archive engine.
//
// This file implements real RAR4/RAR5 parsing and extraction on top of the
// Phase 3E-A foundation (see docs/adr/0024-native-cbr-libarchive.md and
// shelfos_cbr_jni.cpp, which this file does not modify). It is a generic
// archive engine: it knows nothing about LibraryItem, PublicationFormat,
// PageSource, the reader, Compose, or ComicInfo.xml. See
// docs/PHASE_3_IMPLEMENTATION_PLAN.md's 3E-B record for the full design
// rationale; this header summarizes only the load-bearing decisions.
//
// FD OWNERSHIP: nativeOpen() takes ownership of the caller's source file
// descriptor the instant it is called, on every path (success AND every
// failure path). The Kotlin side (NativeRarSession.open) must relinquish
// Java-level ownership (ParcelFileDescriptor.detachFd()) before calling.
// archive_read_open_fd() itself never closes the fd it is given (confirmed
// by reading third_party/libarchive/libarchive/archive_read_open_fd.c's
// file_close(), which only frees its own internal buffer struct) - closing
// the fd is entirely this file's responsibility, exactly once: Session's own
// destructor is the single structural close point, reached either via
// nativeClose()'s `delete session` on success, or via a
// std::unique_ptr<Session> unwinding out of scope on any nativeOpen()
// failure/exception path (see Session's and nativeOpen()'s doc comments).
//
// SEEK/RESTART: RAR (especially solid RAR) cannot be treated as randomly
// seekable per-entry. There is no page cache here (explicitly out of scope
// for 3E-B). Instead, every operation that needs entry N's data restarts
// a brand-new archive_read from the beginning of the owned fd (lseek to 0,
// archive_read_open_fd again) and sequentially skips to the target physical
// entry. This is intentionally simple/safe over fast; 3E-D may add caching
// later behind this same contract.
//
// HANDLE MODEL: the opaque jlong handle returned to Kotlin is a
// reinterpret_cast of a heap-allocated Session*. There is no native
// handle-validity registry and no global mutable state: Kotlin
// (NativeRarSession) is the sole, single owner of that jlong, and is
// responsible (per its own doc comment) for atomic set/clear-on-close,
// idempotent close, and rejecting post-close operations BEFORE calling into
// native code. This file trusts that contract and only defends against
// obviously-invalid handles (<= 0) as a cheap sanity check, not as a
// substitute for it.
//
// THREADING: one archive_read object is ever alive at a time per Session,
// scoped to a single call (open's metadata pass, or one extractEntry call).
// Kotlin serializes all operations on a given session with its own lock.
// This file performs no cross-call synchronization itself; it assumes (per
// the documented Kotlin contract) that it is never entered concurrently for
// the same handle.
//
// NO ARCHIVE_WRITE: this file links no archive_write_* API at all.

#include <jni.h>
#include <unistd.h>
#include <errno.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "archive.h"
#include "archive_entry.h"

namespace {

// Error categories shared (by ordinal) with Kotlin's NativeRarError enum.
// 0 is reserved for "no error" in the extractEntry return channel; nativeOpen
// never returns 0 as a handle (a real heap pointer is never zero), so on
// that call 0 is unambiguous as "not an error code" vs "not a handle" by
// context alone - nativeOpen's own failure path always returns a NEGATIVE
// encoding of one of these ordinals (never 0), see nativeOpen below.
enum class ErrorCode : jint {
    OK = 0,
    INVALID_ARGUMENT = 1,
    IO = 2,
    NOT_SEEKABLE = 3,
    CORRUPT = 4,
    PROTECTED = 5,
    UNSUPPORTED = 6,
    NATIVE_INTERNAL = 7,
};

// Bounded streaming buffer for extraction. Fixed size, never derived from
// archive-claimed entry size (hostile input: a malicious/corrupt archive
// entry's declared size must never size a native allocation).
constexpr size_t kStreamBufferSize = 64 * 1024;  // 64 KiB

struct EntryMeta {
    std::string name;    // Best-effort UTF-8 bytes (see collectEntries()).
    bool nameIsUtf8 = false;
    int type = 2;        // 0 = regular file, 1 = directory, 2 = other/special.
    int64_t size = -1;    // -1 = unknown/unset/negative-and-therefore-distrusted.
};

// Owns both the source fd and the collected entry metadata. The destructor
// is the single, structural point that closes `fd` (if still owned):
// whether a Session is destroyed via `delete` from nativeClose() or via a
// std::unique_ptr<Session> unwinding out of scope on a nativeOpen() failure/
// exception path, exactly the same cleanup runs - no call site needs to
// remember to close the fd itself once a Session owns it.
struct Session {
    int fd = -1;
    std::vector<EntryMeta> entries;

    explicit Session(int ownedFd) : fd(ownedFd) {}
    ~Session() {
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
    }
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
};

// RAII wrapper for a short-lived archive_read, used for exactly one
// metadata pass or one extraction call. Always frees on every path.
class ArchiveReadScope {
public:
    explicit ArchiveReadScope(archive* a) : archive_(a) {}
    ~ArchiveReadScope() {
        if (archive_ != nullptr) {
            archive_read_free(archive_);
        }
    }
    ArchiveReadScope(const ArchiveReadScope&) = delete;
    ArchiveReadScope& operator=(const ArchiveReadScope&) = delete;
    [[nodiscard]] archive* raw() const { return archive_; }

private:
    archive* archive_;
};

// Restarts the owned fd to its very beginning, distinguishing "this fd
// isn't even a valid open descriptor" (EBADF -> IO) from "this descriptor
// is open but genuinely not seekable" (e.g. a pipe, ESPIPE -> NOT_SEEKABLE)
// - both are real, distinct categories a caller can act on differently.
ErrorCode restartFd(int fd) {
    if (lseek(fd, 0, SEEK_SET) == 0) {
        return ErrorCode::OK;
    }
    return (errno == EBADF) ? ErrorCode::IO : ErrorCode::NOT_SEEKABLE;
}

// Opens a fresh archive_read positioned at the start of `fd`, registering
// ONLY RAR4 and RAR5 support (never archive_read_support_format_all, never
// archive_read_support_format_filter_all - this engine reads nothing else).
// Returns nullptr and sets *err on failure; caller owns the returned
// archive* (none returned on failure).
archive* openReaderAtStart(int fd, ErrorCode* err) {
    ErrorCode restartErr = restartFd(fd);
    if (restartErr != ErrorCode::OK) {
        *err = restartErr;
        return nullptr;
    }
    archive* a = archive_read_new();
    if (a == nullptr) {
        *err = ErrorCode::NATIVE_INTERNAL;
        return nullptr;
    }
    if (archive_read_support_format_rar(a) != ARCHIVE_OK ||
        archive_read_support_format_rar5(a) != ARCHIVE_OK) {
        archive_read_free(a);
        *err = ErrorCode::NATIVE_INTERNAL;
        return nullptr;
    }
    if (archive_read_open_fd(a, fd, kStreamBufferSize) != ARCHIVE_OK) {
        // Verified by reading archive_read.c's choose_format(): with only
        // RAR+RAR5 registered, a non-RAR stream fails format bidding
        // DURING archive_read_open_fd() itself (via archive_read_open1(),
        // not deferred to the first archive_read_next_header() call), and
        // archive_set_error() tags that specific failure with
        // ARCHIVE_ERRNO_FILE_FORMAT. That macro resolves per-platform
        // (archive_platform.h); this vendored build's generated config.h
        // confirms HAVE_EILSEQ=1/HAVE_EFTYPE=0 for every ABI, so it is
        // EILSEQ here. EINVAL is also accepted defensively in case a
        // future reconfigure ever resolves it differently.
        int archiveErrno = archive_errno(a);
        archive_read_free(a);
        *err = (archiveErrno == EILSEQ || archiveErrno == EINVAL)
                   ? ErrorCode::UNSUPPORTED
                   : ErrorCode::IO;
        return nullptr;
    }
    *err = ErrorCode::OK;
    return a;
}

// Classifies a non-OK/non-WARN/non-EOF archive_read_next_header() result.
// Checked AFTER the failure so a fully header-encrypted archive (which
// never yields a single successful header) still maps to PROTECTED rather
// than CORRUPT/UNSUPPORTED - "don't require a successful entry list first".
ErrorCode classifyHeaderFailure(archive* a, bool anyHeaderSucceededYet) {
    if (archive_read_has_encrypted_entries(a) == 1) {
        return ErrorCode::PROTECTED;
    }
    // No header ever succeeded: most likely this fd's content simply isn't
    // RAR/RAR5 at all (format bidding failed on the very first header).
    // At least one header succeeded already: a later structural failure in
    // what IS a real RAR/RAR5 stream - treat as corruption.
    return anyHeaderSucceededYet ? ErrorCode::CORRUPT : ErrorCode::UNSUPPORTED;
}

// Full sequential metadata pass: reads every header, records entry
// metadata, skips each entry's data (never decompresses it here), and
// determines whether any entry is encrypted. No partial-success
// interpretation: if ANY entry is encrypted, the whole result is
// ErrorCode::PROTECTED and `out` is left in an unspecified (but destructed
// cleanly) state - callers must check the return value before using `out`.
ErrorCode collectEntries(int fd, std::vector<EntryMeta>* out) {
    ErrorCode err;
    archive* a = openReaderAtStart(fd, &err);
    if (a == nullptr) {
        return err;
    }
    ArchiveReadScope scope(a);

    bool anyHeaderSucceeded = false;
    bool anyEncrypted = false;

    for (;;) {
        archive_entry* ae = nullptr;
        int rc = archive_read_next_header(a, &ae);
        if (rc == ARCHIVE_EOF) {
            break;
        }
        if (rc != ARCHIVE_OK && rc != ARCHIVE_WARN) {
            // ARCHIVE_WARN: partial success, entry/data still usable - the
            // libarchive convention this engine relies on. Anything else
            // (ARCHIVE_RETRY/ARCHIVE_FAILED/ARCHIVE_FATAL) aborts the whole
            // pass deterministically; this engine does not implement a
            // retry loop for any of them.
            return classifyHeaderFailure(a, anyHeaderSucceeded);
        }
        anyHeaderSucceeded = true;

        if (archive_entry_is_data_encrypted(ae) == 1 ||
            archive_entry_is_metadata_encrypted(ae) == 1) {
            anyEncrypted = true;
        }

        EntryMeta meta;
        const char* nameUtf8 = archive_entry_pathname_utf8(ae);
        if (nameUtf8 != nullptr) {
            meta.name.assign(nameUtf8);
            meta.nameIsUtf8 = true;
        } else {
            const char* nameRaw = archive_entry_pathname(ae);
            meta.name.assign(nameRaw != nullptr ? nameRaw : "");
            meta.nameIsUtf8 = false;
        }

        __LA_MODE_T ft = archive_entry_filetype(ae);
        if (ft == AE_IFREG) {
            meta.type = 0;
        } else if (ft == AE_IFDIR) {
            meta.type = 1;
        } else {
            meta.type = 2;
        }

        if (archive_entry_size_is_set(ae)) {
            int64_t sz = archive_entry_size(ae);
            meta.size = (sz >= 0) ? sz : -1;  // negative declared size: distrust it.
        } else {
            meta.size = -1;
        }

        out->push_back(std::move(meta));

        int skipRc = archive_read_data_skip(a);
        if (skipRc != ARCHIVE_OK && skipRc != ARCHIVE_EOF && skipRc != ARCHIVE_WARN) {
            return ErrorCode::CORRUPT;
        }
    }

    if (archive_read_has_encrypted_entries(a) == 1) {
        anyEncrypted = true;
    }

    return anyEncrypted ? ErrorCode::PROTECTED : ErrorCode::OK;
}

// Restarts from the beginning of `fd` and sequentially skips to physical
// entry `index`, streaming its data into `destFd` through a fixed-size
// buffer. `destFd` is a BORROWED descriptor: this function never closes it,
// on any path - the caller retains ownership. The archive pathname is
// never used as an output path; writes always go to the caller-supplied fd.
ErrorCode extractEntry(int fd, const std::vector<EntryMeta>& entries, int index, int destFd) {
    if (index < 0 || static_cast<size_t>(index) >= entries.size()) {
        return ErrorCode::INVALID_ARGUMENT;
    }
    if (entries[static_cast<size_t>(index)].type != 0) {
        // Never extract directories or special/symlink/hardlink entries -
        // no link creation, no materializing special filesystem objects.
        return ErrorCode::INVALID_ARGUMENT;
    }

    ErrorCode err;
    archive* a = openReaderAtStart(fd, &err);
    if (a == nullptr) {
        return err;
    }
    ArchiveReadScope scope(a);

    bool anyHeaderSucceeded = false;
    int physical = 0;
    bool found = false;
    for (;;) {
        archive_entry* ae = nullptr;
        int rc = archive_read_next_header(a, &ae);
        if (rc == ARCHIVE_EOF) {
            break;
        }
        if (rc != ARCHIVE_OK && rc != ARCHIVE_WARN) {
            return classifyHeaderFailure(a, anyHeaderSucceeded);
        }
        anyHeaderSucceeded = true;
        if (physical == index) {
            found = true;
            break;
        }
        int skipRc = archive_read_data_skip(a);
        if (skipRc != ARCHIVE_OK && skipRc != ARCHIVE_EOF && skipRc != ARCHIVE_WARN) {
            return ErrorCode::CORRUPT;
        }
        physical++;
    }
    if (!found) {
        // The metadata pass and this re-scan disagree on entry count - only
        // possible if the underlying fd changed between calls, which this
        // engine does not support/expect. Fail deterministically rather
        // than guess.
        return ErrorCode::NATIVE_INTERNAL;
    }

    std::vector<uint8_t> buffer(kStreamBufferSize);  // fixed size, never entry-size-derived.
    for (;;) {
        la_ssize_t n = archive_read_data(a, buffer.data(), buffer.size());
        if (n < 0) {
            return ErrorCode::CORRUPT;
        }
        if (n == 0) {
            break;  // EOF for this entry.
        }

        size_t written = 0;
        while (written < static_cast<size_t>(n)) {
            ssize_t w = write(destFd, buffer.data() + written, static_cast<size_t>(n) - written);
            if (w < 0) {
                if (errno == EINTR) {
                    continue;  // Transient signal interruption: retry the same write.
                }
                return ErrorCode::IO;  // Any other failure (e.g. closed/unwritable destFd): stop.
            }
            written += static_cast<size_t>(w);  // Handle a real partial write.
        }
    }

    return ErrorCode::OK;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeOpen(
        JNIEnv* /*env*/, jclass /*clazz*/, jint fd) {
    if (fd < 0) {
        // Never a real fd Kotlin could have owned via detachFd(); nothing to close.
        return -static_cast<jlong>(ErrorCode::INVALID_ARGUMENT);
    }

    // `session` is declared here (function scope, not inside the try block)
    // specifically so the catch block below can inspect it: once
    // std::make_unique<Session>(fd) has returned, this unique_ptr is the
    // single owner of both the Session and `fd`, and letting it fall out of
    // scope - on ANY return out of this function, success or failure -
    // automatically and exactly-once runs Session's destructor (closes fd,
    // frees entries). No path below ever calls close(fd) or delete session
    // manually once `session` is non-null.
    std::unique_ptr<Session> session;
    try {
        session = std::make_unique<Session>(fd);

        ErrorCode err = collectEntries(fd, &session->entries);
        if (err != ErrorCode::OK) {
            // `session` destructs on return, closing fd and freeing entries.
            return -static_cast<jlong>(err);
        }

        // A real heap pointer is never 0 and (on every Android ABI's
        // address space) never large enough to look negative as a signed
        // 64-bit value, so this is unambiguous against the negative error
        // encoding above. release() hands fd/entry ownership to the opaque
        // handle now returned to Kotlin; nativeClose() is the only
        // remaining path that may destroy this Session.
        return reinterpret_cast<jlong>(session.release());
    } catch (...) {
        // No C++ exception may cross the JNI boundary (e.g. std::bad_alloc
        // from make_unique or from collectEntries growing its entry
        // vector/strings). Ownership of `fd` depends on exactly how far we
        // got: if `session` is still null, std::make_unique<Session>(fd)
        // itself never completed constructing a Session, so `fd` was never
        // taken into any Session's care and this catch must close it here,
        // exactly once. If `session` is non-null, it already owns `fd`
        // (the constructor ran) and its destructor - which fires
        // automatically when `session` goes out of scope at this function's
        // return below - is the sole, single point that closes `fd`; this
        // catch block must NOT also close(fd) in that case, or it would be
        // double-closed.
        if (!session) {
            ::close(fd);
        }
        return -static_cast<jlong>(ErrorCode::NATIVE_INTERNAL);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeClose(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    try {
        if (handle <= 0) {
            return;
        }
        auto* session = reinterpret_cast<Session*>(handle);
        // Session's destructor closes fd (if still owned) exactly once;
        // see the Session struct's doc comment.
        delete session;
    } catch (...) {
        // close() must never throw across the JNI boundary.
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeEntryCount(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle <= 0) {
        return -1;
    }
    auto* session = reinterpret_cast<Session*>(handle);
    return static_cast<jint>(session->entries.size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeEntryType(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint index) {
    if (handle <= 0) {
        return -1;
    }
    auto* session = reinterpret_cast<Session*>(handle);
    if (index < 0 || static_cast<size_t>(index) >= session->entries.size()) {
        return -1;
    }
    return session->entries[static_cast<size_t>(index)].type;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeEntrySize(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint index) {
    if (handle <= 0) {
        return -1;
    }
    auto* session = reinterpret_cast<Session*>(handle);
    if (index < 0 || static_cast<size_t>(index) >= session->entries.size()) {
        return -1;
    }
    return session->entries[static_cast<size_t>(index)].size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeEntryIsNameUtf8(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint index) {
    if (handle <= 0) {
        return JNI_FALSE;
    }
    auto* session = reinterpret_cast<Session*>(handle);
    if (index < 0 || static_cast<size_t>(index) >= session->entries.size()) {
        return JNI_FALSE;
    }
    return session->entries[static_cast<size_t>(index)].nameIsUtf8 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeEntryName(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jint index) {
    try {
        if (handle <= 0) {
            return env->NewByteArray(0);
        }
        auto* session = reinterpret_cast<Session*>(handle);
        if (index < 0 || static_cast<size_t>(index) >= session->entries.size()) {
            return env->NewByteArray(0);
        }
        const std::string& name = session->entries[static_cast<size_t>(index)].name;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(name.size()));
        if (result == nullptr) {
            return nullptr;  // OutOfMemoryError already pending in the JVM.
        }
        if (!name.empty()) {
            env->SetByteArrayRegion(
                    result, 0, static_cast<jsize>(name.size()),
                    reinterpret_cast<const jbyte*>(name.data()));
        }
        return result;
    } catch (...) {
        return env->NewByteArray(0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_d4guilar_shelfos_core_files_NativeRarSession_nativeExtractEntry(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint index, jint destFd) {
    try {
        if (handle <= 0) {
            return static_cast<jint>(ErrorCode::INVALID_ARGUMENT);
        }
        if (destFd < 0) {
            return static_cast<jint>(ErrorCode::INVALID_ARGUMENT);
        }
        auto* session = reinterpret_cast<Session*>(handle);
        ErrorCode err = extractEntry(session->fd, session->entries, index, destFd);
        return static_cast<jint>(err);
    } catch (...) {
        return static_cast<jint>(ErrorCode::NATIVE_INTERNAL);
    }
}
