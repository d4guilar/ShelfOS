# ADR-0002: Local-First and Accountless Core

## Status
Accepted

## Context
ShelfOS exists primarily to read files the user already owns.

## Decision
Core ShelfOS functionality requires no ShelfOS account and no backend connection.
ShelfOS is offline-complete and online-enhanced: core importing, browsing, reading,
state and future processing such as OCR retain local paths; online services may
enrich the library without determining whether it works.

## Consequences
- reading works offline
- lower operational complexity
- improved privacy
- optional future services must not become required dependencies
- core processing approaches zero marginal infrastructure cost per user
