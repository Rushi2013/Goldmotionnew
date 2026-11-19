#!/usr/bin/env python3
"""Print one '::error title=...::message' workflow command per build/test error from a Gradle log.

`junit_failures.py` only covers the case where tests actually ran. When compilation or the
test task itself blows up, no JUnit XML is produced, so this script scrapes the captured
gradle output instead. Annotations are readable through the checks API even when the raw
job log cannot be downloaded.
"""
import re
import sys

MAX_ERRORS = 40

# Gradle/Kotlin diagnostics we care about, most specific first.
PATTERNS = [
    ("Kotlin", re.compile(r"^e: .*")),
    ("Build", re.compile(r"^(?:FAILURE: .*|> Task .* FAILED|.*Execution failed for task .*)")),
    ("Test", re.compile(r"^\s*.*(?:AssertionError|expected:<|Exception:).*$")),
    ("Gradle", re.compile(r"^(?:\* What went wrong:.*|\s+> .*|\s+.*error:.*)$")),
]


def esc(text: str, limit: int = 900) -> str:
    """Workflow-command escaping: %25 -> %, %0D -> CR, %0A -> LF."""
    text = (text or "").replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return text[:limit]


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "test.log"
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError as exc:
        print("::warning title=Gradle log::" + esc(str(exc)))
        return 0

    emitted = 0
    seen = set()
    for kind, pattern in PATTERNS:
        for line in lines:
            text = line.strip()
            if not text or text in seen or not pattern.match(line):
                continue
            seen.add(text)
            print("::error title=%s::%s" % (kind, esc(text)))
            emitted += 1
            if emitted >= MAX_ERRORS:
                print("::error title=Gradle::stopping at %d diagnostics — see the job summary" % MAX_ERRORS)
                return 0

    if emitted == 0:
        print("No Gradle diagnostics matched")
    return 0


if __name__ == "__main__":
    sys.exit(main())
