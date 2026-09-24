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
