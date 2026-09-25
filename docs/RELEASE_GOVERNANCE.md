# ShelfOS Branching and Release Governance

## Principle

Public collaboration does not mean uncontrolled releases.

Git branches, forks, and pull requests are development mechanisms. Google Play and App Store releases are separate, signed distribution processes.

## Recommended branches

### `main`

- protected
- expected to compile
- canonical integration branch
- no direct contributor pushes
- changes arrive through pull requests
- required checks before merge

### Feature/fix branches

Examples:

```text
feat/library-grid
feat/metadata-openlibrary
fix/cbz-natural-sort
theme/paper-library
```

Branches may live in contributor forks or the main repository depending on permissions.

### Release tags

Official versions should use immutable Git tags:

```text
v0.1.0
v0.2.0
v1.0.0
```

A long-lived `develop` branch is not required initially.

## Pull request policy

Pull requests should describe the change, reference relevant docs/ADRs, include tests where appropriate, preserve local-first principles, pass CI, and receive maintainer approval.

Architectural changes should update documentation and add/update an ADR where necessary.

## Branch protection

Recommended GitHub settings for `main`:

- require pull request before merging
- require at least one approval once outside contributors appear
- require status checks
- block force pushes
- block deletion
- optionally require resolved conversations

## Android official releases

The official Android application is identified by its package/application ID, official signing identity, Google Play listing, and release process.

A fork cannot update the official Play Store app without access to the relevant release credentials/signing process. Public source does not grant Play Console access.

Never commit signing keys, keystore passwords, Play credentials, or API secrets.

## Future iOS official releases

The official iOS application will depend on its bundle identifier, Apple Developer/App Store Connect access, Apple signing certificates/profiles, and official release pipeline.

A GitHub fork does not gain these credentials because the source is public.

Never commit Apple signing credentials or App Store API private keys.

## Community builds

Community builds are allowed subject to the project license. They are not automatically official ShelfOS releases.

Where practical, public redistributors should use distinct package/bundle identifiers, distinguishable branding, and clear disclosure that the build is unofficial.

## CI/CD

Early:

```text
PR
 ↓
format/lint
 ↓
unit tests
 ↓
build debug APK
```

Later official Android:

```text
tag/release approval
 ↓
release build
 ↓
signing
 ↓
AAB artifact
 ↓
internal Play track
 ↓
production promotion
```

Future iOS:

```text
tag/release approval
 ↓
Xcode build/archive
 ↓
sign
 ↓
TestFlight
 ↓
App Store review
```

## Security boundary

```text
PUBLIC SOURCE
      ↓
MAINTAINER-APPROVED RELEASE COMMIT/TAG
      ↓
PRIVATE SIGNING + STORE CREDENTIALS
      ↓
OFFICIAL DISTRIBUTION
```

This is why an open repository does not let a random contributor alter the Play Store or App Store version.
