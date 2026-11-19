#!/usr/bin/env python3
"""Print one '::error title=...::message' workflow command per failing JUnit test case.

GitHub renders those as check-run annotations, which are readable through the REST API
even when the raw job log is not downloadable.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def esc(text: str, limit: int = 900) -> str:
    """Workflow-command escaping: %25 -> %, %0D -> CR, %0A -> LF."""
    text = (text or "").replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return text[:limit]


def main() -> int:
    root_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest"
    files = sorted(glob.glob(os.path.join(root_dir, "TEST-*.xml")))
    if not files:
        print("::warning title=JUnit::no test result XML found in " + root_dir)
        return 0

    total_failures = 0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except Exception as exc:  # noqa: BLE001
            print("::warning title=JUnit unreadable::" + esc(os.path.basename(path) + " " + str(exc)))
            continue
        for tc in root.iter("testcase"):
            for tag in ("failure", "error"):
                node = tc.find(tag)
                if node is None:
                    continue
                total_failures += 1
                name = "%s.%s" % (tc.get("classname") or "?", tc.get("name") or "?")
                message = (node.get("message") or "").strip()
                body = (node.text or "").strip()
                detail = message or body
                print("::error title=%s::%s" % (esc(name, 120), esc(detail)))

    if total_failures:
        print("::error title=Unit tests::%d test(s) failed — see the annotations above" % total_failures)
    else:
        print("All unit tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
