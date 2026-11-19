#!/usr/bin/env python3
"""Print a compact pass/fail summary of JUnit XML files for the Actions job summary."""
import os
import sys
import glob
import xml.etree.ElementTree as ET


def main() -> int:
    root_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest"
    files = sorted(glob.glob(os.path.join(root_dir, "TEST-*.xml")))
    if not files:
        print("no JUnit XML found under " + root_dir)
        return 0

    total = fails = errs = skipped = 0
    for path in files:
        try:
            r = ET.parse(path).getroot()
        except Exception as exc:  # noqa: BLE001 - report and continue
            print("UNREADABLE", path, exc)
            continue
        t = int(r.get("tests") or 0)
        f = int(r.get("failures") or 0)
        e = int(r.get("errors") or 0)
        k = int(r.get("skipped") or 0)
        total += t
        fails += f
        errs += e
        skipped += k
        name = os.path.basename(path)
        print("%-46s tests=%-3d failures=%-2d errors=%-2d skipped=%-2d" % (name, t, f, e, k))
        for tc in r.iter("testcase"):
            for tag in ("failure", "error"):
                node = tc.find(tag)
                if node is None:
                    continue
                print("   FAIL %s :: %s" % (tc.get("classname"), tc.get("name")))
                msg = (node.get("message") or "").strip()
                if msg:
                    print("        message: " + msg.replace("\n", " ")[:400])
                body = (node.text or "").strip()
                if body:
                    first = body.splitlines()[0]
                    print("        at:      " + first[:300])

    print("")
    print("TOTAL tests=%d failures=%d errors=%d skipped=%d" % (total, fails, errs, skipped))
    return 0 if fails == 0 and errs == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
