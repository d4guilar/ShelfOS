"""Conservative read-only HTTP client for the provider lab.

GET only, one request in flight, a minimum interval per host, bounded body, limited retries,
Retry-After respected, and the provider is stopped on 403/429. No redirects across hosts, no
cookies, no credentials. The transport is injectable so tests never touch the network.
"""
import time
import urllib.error
import urllib.request

UA = "ShelfOS-provider-lab/0.1 (+https://github.com/d4guilar/ShelfOS; research spike, read-only, not a production client)"


class Blocked(Exception):
    """The provider answered 403/429 (or exhausted its budget); further requests are refused."""


class Result:
    def __init__(self, status, headers, body, elapsed, error=None):
        self.status, self.headers, self.body, self.elapsed, self.error = status, headers, body, elapsed, error

    @property
    def ok(self):
        return self.error is None and self.status is not None and 200 <= self.status < 300


def urllib_transport(url, headers, timeout, max_bytes):
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read(max_bytes + 1)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read(max_bytes + 1)


class Client:
    def __init__(self, host, min_interval=1.0, timeout=20.0, max_bytes=2_000_000, max_requests=200, retries=2,
                 transport=urllib_transport, sleep=time.sleep, clock=time.monotonic, accept="application/json", ua=UA):
        self.host, self.min_interval, self.timeout, self.max_bytes = host, min_interval, timeout, max_bytes
        self.max_requests, self.retries, self.transport, self.sleep, self.clock = max_requests, retries, transport, sleep, clock
        self.headers = {"User-Agent": ua, "Accept": accept, "Accept-Encoding": "identity"}
        self.blocked = None
        self.requests = 0
        self.log = []   # (url, status, seconds, error)
        self._last = None

    def get(self, url):
        if not url.startswith("https://" + self.host + "/"):
            raise ValueError("refusing a request outside the provider host: " + url)
        if self.blocked:
            raise Blocked(self.blocked)
        attempt = 0
        while True:
            if self.requests >= self.max_requests:
                self.blocked = "request budget exhausted"
                raise Blocked(self.blocked)
            if self._last is not None:
                wait = self.min_interval - (self.clock() - self._last)
                if wait > 0:
                    self.sleep(wait)
            self.requests += 1
            t0 = self.clock()
            self._last = t0
            try:
                status, headers, body = self.transport(url, self.headers, self.timeout, self.max_bytes)
                err = "response larger than %d bytes" % self.max_bytes if len(body) > self.max_bytes else None
            except Exception as e:   # transport failure is never "no result"
                status, headers, body, err = None, {}, b"", "%s: %s" % (type(e).__name__, e)
            dt = self.clock() - t0
            self.log.append((url, status, dt, err))
            if status in (403, 429):
                ra = _retry_after(headers)
                self.blocked = "HTTP %d%s" % (status, " (Retry-After %ss)" % ra if ra is not None else "")
                raise Blocked(self.blocked)
            transient = err is not None and status is None or status in (500, 502, 503, 504)
            if transient and attempt < self.retries:
                attempt += 1
                ra = _retry_after(headers)
                self.sleep(ra if ra is not None else 2.0 * attempt)
                continue
            if err is None and status is not None and status >= 400:
                err = "HTTP %d" % status
            return Result(status, headers, body, dt, err)


def _retry_after(headers):
    for k, v in (headers or {}).items():
        if k.lower() == "retry-after":
            try:
                return max(0.0, float(v))
            except ValueError:
                return None
    return None
