# Security Policy

ShelfOS is early in development.

## Reporting vulnerabilities

Use GitHub's private vulnerability reporting on this repository when enabled:
`https://github.com/d4guilar/ShelfOS/security/advisories/new`.
If unavailable, open a minimal issue requesting a private contact channel;
do not publish exploit details or private publication data.
There are no supported production releases yet.

## Secrets

Never commit:

- Android keystores
- signing passwords
- Play Console credentials
- Apple signing credentials
- App Store API private keys
- private API tokens
- service-account credentials

Use ignored local configuration or protected CI secrets.

## User files

ShelfOS handles personal publications and documents.

Security-sensitive changes involving file access, archive parsing, metadata, or network transmission require extra review.

Future generic ZIP library import must treat selected archives as untrusted input:
prevent absolute/path-traversal extraction, bound file counts, nesting, expanded
size and compression ratios, handle duplicate names and malformed/encrypted entries,
and isolate per-entry failures. Never recursively expand arbitrary nested archives
or modify/delete the original ZIP. The detailed contract is in
[`DATA_INGESTION.md`](docs/features/DATA_INGESTION.md#archive-safety).
