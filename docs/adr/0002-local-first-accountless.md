# ADR-0002: Local-First and Accountless Core

## Status
Accepted

## Context
ShelfOS exists primarily to read files the user already owns.

## Decision
Core ShelfOS functionality requires no ShelfOS account and no backend connection.

## Consequences
- reading works offline
- lower operational complexity
- improved privacy
- optional future services must not become required dependencies
