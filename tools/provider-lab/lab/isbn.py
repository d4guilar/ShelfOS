"""ISBN helpers: checksum, conversion and hyphenation variants (stdlib only)."""
import re


def digits(s):
    return re.sub(r"[\s\-]", "", s or "").upper()


def check13(d):
    return len(d) == 13 and d.isdigit() and (10 - sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(d[:12])) % 10) % 10 == int(d[12])


def check10(d):
    if len(d) != 10 or not re.fullmatch(r"\d{9}[\dX]", d):
        return False
    return sum((10 - i) * (10 if c == "X" else int(c)) for i, c in enumerate(d)) % 11 == 0


def to13(d):
    d = digits(d)
    if len(d) == 13:
        return d if check13(d) else None
    if len(d) == 10 and check10(d):
        core = "978" + d[:9]
        return core + str((10 - sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(core)) % 10) % 10)
    return None


def to10(d13):
    if not (d13.startswith("978") and check13(d13)):
        return None
    core = d13[3:12]
    s = sum((10 - i) * int(c) for i, c in enumerate(core)) % 11
    c = (11 - s) % 11
    return core + ("X" if c == 10 else str(c))


def hyphen_variants(d):
    """Every group/registrant/publication split of a 13- or 10-digit ISBN, plus the plain digits.

    Wikidata stores ISBNs as free strings, usually hyphenated by the contributor's own
    ranges, so exact lookup needs the candidate spellings. About 35 per ISBN.
    """
    out = [d]
    if len(d) == 13:
        pre, body = d[:3], d[3:12]
        sep = lambda a: pre + "-" + a
        rest_len = 9
    else:
        pre, body = "", d[:9]
        sep = lambda a: a
        rest_len = 9
    chk = d[-1]
    for g in range(1, 6):
        for r in range(1, 8):
            p = rest_len - g - r
            if p < 1:
                continue
            out.append(sep(body[:g] + "-" + body[g:g + r] + "-" + body[g + r:] + "-" + chk))
    return out
