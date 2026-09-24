# ADR-0013: Metadata Enrichment with Provenance

## Status
Accepted

## Context

ShelfOS should turn local files into recognizable library objects with covers, creators, descriptions, series data, and other publication metadata.

External providers can improve the experience, but provider data may be incomplete or wrong and network access is optional.

## Decision

Metadata enrichment is implemented through replaceable providers coordinated by a ShelfOS-owned service/repository boundary.

Persist metadata provenance.

Manual user edits have the highest precedence and must survive refresh.

Core reading never depends on external metadata providers.

## Consequences

Positive:

- provider independence
- offline resilience
- safer refresh behavior
- transparent confidence/source display
- future comic/manga providers fit the same architecture

Negative:

- metadata model becomes richer
- field provenance or resolved-record provenance must be persisted
- ambiguous matching requires UI
