# Contributing to ShelfOS

Thanks for helping improve ShelfOS.

ShelfOS is an open-source, local-first personal reading system.

## Before starting

Read:

1. `AGENTS.md`
2. `docs/PRODUCT.md`
3. `docs/ARCHITECTURE.md`
4. `docs/ROADMAP.md`
5. relevant feature/design specifications
6. relevant ADRs

## Workflow

1. Open or identify an issue when useful.
2. Fork the repository.
3. Create a focused branch.
4. Make the smallest coherent change.
5. Add/update tests where appropriate.
6. Update docs when behavior or architecture changes.
7. Open a pull request.

Suggested branch names:

```text
feat/<short-name>
fix/<short-name>
docs/<short-name>
theme/<short-name>
refactor/<short-name>
```

## Pull requests

A good PR explains what changed, why, testing performed, known limitations, and
supplies appropriate validation evidence. Concept images and prototype
screenshots remain local-only; do not commit them. Public README screenshots are
deferred until the first fully working version and must use permitted content.

## Product boundaries

Contributions must preserve local-first core behavior, no required account for core use, source-file safety, no reading ads, normal Android navigation, accessibility, the Books/Comics/Manga/Documents model, Favorites as cross-category, and theme structural invariants.

## ShelfOS Plus development

The public repository may contain Plus features.

Debug builds may provide a development-only entitlement override for testing.

Do not add release-build bypasses intended to defeat the official entitlement system.

## Dependencies

For this prototype, run the wrapper tasks `:app:assembleDebug`,
`:app:testDebugUnitTest`, and `:app:lintDebug`. Compile device tests with
`:app:assembleDebugAndroidTest`; execute them using `:app:connectedDebugAndroidTest`
on a connected emulator/device. See `docs/VALIDATION.md` for adaptive/input checks.
Commit generated Room schema JSON with every database change.

Before adding a dependency, document its purpose, license, maintenance status, binary impact, and why an existing platform/project capability is insufficient.

Avoid GPL/AGPL dependencies without explicit architectural/license review.

## AI-assisted contributions

AI-assisted code is welcome, but contributors remain responsible for correctness, licensing, tests, security, maintainability, and understanding submitted changes.

Do not paste code of unclear provenance into ShelfOS.

## License

By contributing, you agree that your contributions are provided under the repository's MPL-2.0 license unless explicitly stated otherwise for a particular asset.

## Library model changes

Follow the [canonical taxonomy](docs/ARCHITECTURE.md#canonical-taxonomy) and the
linked ingestion/PDF/Series/Shelves/Source specifications. Preserve source files and
user decisions. Large imports must work without online metadata, and missing access
must not delete library knowledge. Test interruption/recovery and source changes
when those capabilities are implemented; do not claim future specs are shipped.
