# libarchive upstream RAR test fixtures (test-only)

Vendored `.uu`-encoded (uuencoded) RAR test archives, taken verbatim from the
upstream `libarchive/libarchive` repository at tag `v3.8.9`, exact commit
`27cbc7827172698143e440801fc0ba39ccb4f1f5` (see
`docs/adr/0024-native-cbr-libarchive.md`). The libarchive 2-clause BSD-style
license (`third_party/libarchive/COPYING`) applies to these fixtures as it
does to the rest of the vendored libarchive source.

These are instrumented-test assets only. Nothing in `app/src/main` references
them, and they are not packaged into a release/production APK. They remain
raw `.uu` text in this checkpoint (Phase 3E-A, native dependency foundation)
— decoding them into binary `.rar` files and exercising real RAR parsing is
Phase 3E-B's job, not this one.

| File | Upstream path | Purpose |
| --- | --- | --- |
| `test_read_format_rar.rar.uu` | `libarchive/test/test_read_format_rar.rar.uu` | Plain RAR4 archive (baseline). |
| `test_read_format_rar4_encrypted.rar.uu` | `libarchive/test/test_read_format_rar4_encrypted.rar.uu` | Password-protected RAR4 archive (encryption intentionally unsupported for now; see ADR-0024). |
| `test_read_format_rar5_compressed.rar.uu` | `libarchive/test/test_read_format_rar5_compressed.rar.uu` | Plain, non-solid RAR5 archive. |
| `test_read_format_rar5_solid.rar.uu` | `libarchive/test/test_read_format_rar5_solid.rar.uu` | Solid (non-independently-seekable) RAR5 archive. |
| `test_read_format_rar5_encrypted.rar.uu` | `libarchive/test/test_read_format_rar5_encrypted.rar.uu` | Password-protected RAR5 archive. |

Do not add decoded/binary `.rar` files from these here. Do not add any other
upstream test fixture (for RAR or any other format) without updating this
table and ADR-0024.
