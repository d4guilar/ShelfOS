"""Provider-neutral evaluation record. Unknown stays None; nothing is inferred (no default volume,
no language guessed from a title, no translation)."""
from dataclasses import dataclass, field, asdict
from typing import Optional


@dataclass
class Normalized:
    provider: str
    queried_isbn: str
    status: str = "OK"                  # OK | NO_RESULT | REQUEST_FAILED
    error: Optional[str] = None
    record_id: Optional[str] = None     # inv:... / wd:Q...
    returned_isbns: list = field(default_factory=list)
    kind: Optional[str] = None          # edition | work | other | None (unknown)
    title: Optional[str] = None
    creators: list = field(default_factory=list)
    publisher: Optional[str] = None
    language: Optional[str] = None      # BCP-47-ish code from the provider's own language item
    series: Optional[str] = None
    volume: Optional[str] = None
    published: Optional[str] = None
    edition_type: Optional[str] = None  # only when the response says so
    cover_present: bool = False
    cover_origin: Optional[str] = None  # where the reference points, if the response says (never fetched)
    links: dict = field(default_factory=dict)   # e.g. openlibrary / goodreads ids exposed by the record
    entity_origin: Optional[str] = None # inventaire-native (inv) vs wikidata-backed (wd)
    candidates: int = 0                 # distinct editions returned for the ISBN
    seconds: float = 0.0
    requests: int = 0

    def to_dict(self):
        return asdict(self)
