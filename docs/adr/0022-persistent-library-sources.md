# ADR-0022: Persistent Library Sources

## Status

Accepted, 2026-09-24. Product/architecture decision; not implementation completion.

## Context

An import process ends, but source access, provenance, rescans and reconnection
must remain meaningful across moves, disconnected storage and device migration.

## Decision

LibrarySource is durable origin/access context; ImportSession is process state.
Model folder, managed and future adapter/one-shot Sources through capabilities.
Start with manual scans and reviewable NEW/CHANGED/MOVED/MISSING/UNCHANGED results.
Synchronization is safe and additive: missing Sources never automatically delete
LibraryItems; disconnect never deletes source files. Reconcile moved files to stable
item UUIDs; review changed content before remapping locators or SourceMaps.
Use scoped platform grants, portable domain UUIDs and fingerprint/identifier evidence.
Android URIs are access references, never cross-platform object identity.

## Consequences and sequencing

This extends ADR-0004 and ADR-0011. Backups preserve source definitions and library
knowledge but require permission/reconnection on another device. Multiple Sources
feed one Library; neither category nor Shelf is determined by origin. Real-time
watching is not assumed. Advanced reconciliation and optional scheduled scans are
later milestones, not requirements to implement every Source type immediately.

Detailed source of truth: [LIBRARY_SOURCES](../features/LIBRARY_SOURCES.md).
Integration evidence: [coverage audit](../DECISION_INTEGRATION_AUDIT.md).

