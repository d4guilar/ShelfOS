# ADR-0024: Native CBR dependency foundation (libarchive)

## Status

Accepted 2026-10-07, Phase 3E-A only (`docs/PHASE_3_IMPLEMENTATION_PLAN.md`). This
ADR covers the native/build-system boundary decision for CBR support. It does
**not** mean CBR import works, that solid-archive RAR reading works, or that
JNI archive parsing has been validated — those are later slices (3E-B through
3E-E), currently NOT STARTED.

## Context

`docs/PHASE_3_IMPLEMENTATION_PLAN.md` treats CBR as mandatory Phase 3 scope
(not a someday/optional item), gated behind an explicit dependency-policy
decision because it is ShelfOS's first native/JNI code and its first
dependency requiring license review beyond a Maven POM check (AGENTS.md's
dependency-introduction rule: license compatibility, maintenance status,
Android support, binary size impact, and whether ShelfOS actually needs it).

A `.cbr` file is a RAR archive of sequential page images. Unlike CBZ (handled
today by `core.files.SeekableZip`, a hand-written positional ZIP reader using
the JDK `Inflater`), RAR is not a simple deflate-based container ShelfOS can
reimplement cheaply:

- RAR3/RAR4 and RAR5 are materially different container formats with
  different block structures, checksums and (for RAR5) a different
  compression scheme.
- RAR archives may be solid (entries compressed as one continuous stream,
  not independently seekable) or encrypted. Solid archives in particular
  defeat the "open this one page" random-access model `SeekableZip` relies
  on for ZIP.
- The reference RAR implementation (WinRAR/UnRAR) is proprietary. The
  historical "unrar" source and the "unrar-free"/libunrar derivatives carry
  a UnRAR-License field-of-use restriction (free for use in free software
  readers, but with usage conditions not present in a conventional
  permissive license) and are generally considered GPL-incompatible by
  distributions. AGENTS.md explicitly forbids casually introducing
  GPL/AGPL components and requires ShelfOS not be designed around source
  secrecy. Building ShelfOS's own RAR reader from scratch is a multi-month,
  high-risk undertaking for a format ShelfOS does not control.

A permissively-licensed, independently-implemented, actively maintained RAR
reader was therefore needed, in C, buildable against the NDK.

## Decision

1. **Dependency: libarchive, pinned to tag `v3.8.9`, exact commit
   `27cbc7827172698143e440801fc0ba39ccb4f1f5`.** Confirmed via
   `git ls-remote --tags https://github.com/libarchive/libarchive v3.8.9`,
   which reports the annotated tag object `f1f785cc218bb05876c54680f10d3d4e54575ea2`
   peeling (`^{}`) to commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` — an
   exact match, confirmed independently via a shallow clone of that tag
   landing on that same commit.

   License evidence (read directly from that exact commit):
   - Top-level `COPYING`: libarchive as a whole is a 2-clause BSD-style
     license ("Copyright (c) 2003-2018 <author(s)>", permissive,
     attribution-only). `COPYING` explicitly calls out the handful of files
     under other terms (3-clause UC Regents for the compress filter, public
     domain for `archive_parse_date.c`, CC0/OpenSSL/Apache-2.0 triple-license
     for the BLAKE2 files) — none of the RAR code is in any of those lists.
   - `libarchive/archive_read_support_format_rar.c` header: 2-clause BSD
     ("Copyright (c) 2003-2007 Tim Kientzle", "Copyright (c) 2011 Andres
     Mejia").
   - `libarchive/archive_read_support_format_rar5.c` header: 2-clause BSD
     ("Copyright (c) 2018 Grzegorz Antoniak").
   - Neither file contains an UnRAR-derived-source notice or an UnRAR-License
     field-of-use clause. Both are independent clean-room implementations.
   - No GPL/AGPL text found in either file or in `COPYING`.
   - Both `archive_read_support_format_rar` and `archive_read_support_format_rar5`
     exist in this tagged tree and are callable, confirmed by direct
     source inspection and (this checkpoint) by linking against them.
   - Neither file requires a new optional native dependency to compile for
     this read-only, non-encrypted use: `rar.c` only conditionally touches
     `zlib.h` for CRC32 (with a bundled fallback, `archive_crc32.h`, used
     here since zlib is disabled); `rar5.c` has no crypto/OpenSSL/mbedTLS
     reference at all in this version. No new optional native dependency
     (OpenSSL, zstd, lz4, xz, bzip2, expat, libxml2) was required or added.

2. **Vendor upstream source directly into the repository tree
   (`third_party/libarchive/`)**, not a git submodule, not a build-time
   network fetch. A reasoned subset of the exact tagged commit's tree: the
   `libarchive/` source directory (excluding its 16&nbsp;MB `test/` fixture
   corpus — all formats, not just RAR, and irrelevant to this checkpoint),
   the top-level `CMakeLists.txt`, the `build/cmake` and `build/version`
   files it reads, the `contrib/android` headers it conditionally includes
   for Android, `COPYING`, and placeholder `CMakeLists.txt` stub files for
   `cat/`, `tar/`, `cpio/`, `unzip/` and the `test/` subdirectories (each
   fully guarded behind `ENABLE_CAT`/`ENABLE_TAR`/`ENABLE_CPIO`/`ENABLE_UNZIP`/
   `ENABLE_TEST`, all forced `OFF`) so upstream's own unconditional
   `add_subdirectory()` calls resolve without pulling in any CLI-tool or
   test source. Upstream's own CMake build system is reused as-is via
   `add_subdirectory()`, with every optional-dependency and CLI/test option
   forced off through cache variables set before the subdirectory is added —
   this is more maintainable than reinventing libarchive's platform
   detection (`archive_platform.h`/`config.h` generation) by hand, per
   AGENTS.md's "prefer maintainable, conventional architecture" rule.
   No source file was modified; copyright headers and `COPYING` are
   preserved exactly as vendored.

3. **ShelfOS-owned JNI bridge, native build via NDK/CMake**, producing one
   ShelfOS-owned shared library, conceptually `libshelfos_cbr.so`
   (`app/src/main/cpp/CMakeLists.txt`, target `shelfos_cbr`), which
   statically links libarchive's `archive_static` target. No separate
   `libarchive.so` is shipped. The upstream CMake build is forced to
   `BUILD_SHARED_LIBS OFF` and `ENABLE_INSTALL OFF`, and no `bsdtar`/
   `bsdcpio`/`bsdcat`/`bsdunzip` executable or writer-CLI tool is built
   (`ENABLE_TAR`/`ENABLE_CPIO`/`ENABLE_CAT`/`ENABLE_UNZIP` all `OFF`).

4. **Read-only RAR4 and RAR5 only.** No libarchive writer API is exposed or
   linked meaningfully (the writer sources are compiled as part of
   `archive_static` because libarchive does not offer a reader-only build
   mode, but no writer entry point is called or exposed through the JNI
   bridge). No shell/external-tool execution, no generic "run any archive
   command" interface. Password-protected/encrypted RAR is intentionally
   unsupported for now — `rar5.c` has no crypto dependency wired in this
   version, and RAR4 encryption is not registered or exercised.

5. **This checkpoint (3E-A) implements no archive I/O at all.** The JNI
   surface is a version query and a side-effect-free capability probe
   (create/free an `archive_read` object, register the RAR4 and RAR5
   format handlers, report success). Opening archives, enumerating entries,
   extracting pages, and page caching are explicitly deferred to 3E-B
   (`domain.importing`/`data.library` work) and beyond. The original
   user's source `.cbr` file remains untouched by ShelfOS at every layer —
   consistent with AGENTS.md's "never modify, rewrite, or replace the
   user's source publication" rule, which applies as much to a future
   reader as to this foundation.

## Consequences

- ShelfOS now has native/JNI surface for the first time: a new crash and
  security boundary (malformed/adversarial archives are untrusted input,
  per `docs/features/DATA_INGESTION.md`'s "entries are untrusted" rule,
  to be enforced fully when 3E-B adds real parsing). This foundation's own
  JNI code is written to fail deterministically rather than abort/throw
  across the JNI boundary, and holds no global mutable native state, but it
  has not yet been exercised against a real archive.
- Three ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`; no 32-bit `x86`) must now
  be built and packaged for every future debug/release build, increasing
  build time and APK/AAB size. `docs/VALIDATION.md`'s 3E-A entry records
  the measured size delta for this checkpoint.
- A vendored native dependency needs a notice entry distinct from the
  existing Maven dependency inventory; see `docs/DEPENDENCIES.md` and
  `docs/DEPENDENCY_LICENSES.csv`. An in-app "Open Source Licenses" screen
  surfacing that notice is not built in this checkpoint — only planned.
- This is ShelfOS's first dependency carrying its own upstream test
  fixtures vendored for future use (4-5 `.uu`-encoded RAR fixtures from the
  same exact tagged commit, under a test-only source set, not production).
  They are inert `.uu` text in this checkpoint; decoding and using them is
  3E-B's job.
- Independent (Codex/administrator) review of this native/dependency
  boundary is required before this checkpoint is considered acceptable for
  merge, consistent with how 3D and other large slices in
  `docs/PHASE_3_IMPLEMENTATION_PLAN.md` are gated.
- Physical ARM hardware validation was not performed in this checkpoint;
  only emulator (x86_64) and host-side three-ABI compilation were
  validated. Physical-device validation remains Phase 3F scope per the
  existing plan.
