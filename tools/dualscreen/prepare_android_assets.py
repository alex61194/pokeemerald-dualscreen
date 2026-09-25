#!/usr/bin/env python3
"""Generate binary assets referenced by the Android sources, without building a ROM.

Run from the repository root, after `make tools` and `make generated`.
The normal Makefile owns conversion rules; scaninc discovers their targets.
"""

import os
from pathlib import Path
import subprocess
import tempfile


def main():
    dependencies = set()
    sources = sorted(Path("src").rglob("*.c")) + sorted(Path("gflib").rglob("*.c"))
    sources += sorted(Path("data").glob("*.s"))
    sources += sorted(Path("sound/songs").rglob("*.s"))
    for source in sources:
        result = subprocess.run(
            ["tools/scaninc/scaninc", "-I", "include", "-I", "gflib", str(source)],
            check=True, capture_output=True, text=True,
        )
        dependencies.update(line for line in result.stdout.splitlines() if line)

    targets = sorted(path for path in dependencies
                     if Path(path).suffix not in {".c", ".h", ".inc", ".s"})
    targets += [str(path.with_suffix(".s")) for path in sorted(Path("sound/songs/midi").glob("*.mid"))]
    # A temporary makefile avoids OS command-line length limits on a fresh clone.
    with tempfile.NamedTemporaryFile(mode="w", suffix=".mk", delete=False) as makefile:
        make_path = makefile.name
        makefile.write("include Makefile\n.PHONY: android-assets\nandroid-assets: "
                       + " ".join(targets) + "\n")
    try:
        jobs = str(os.cpu_count() or 4)
        subprocess.run(["make", "--no-print-directory", "-j", jobs,
                        "-f", make_path, "NODEP=1", "SETUP_PREREQS=0", "MODERN=1",
                        "android-assets"], check=True)
    finally:
        if os.path.exists(make_path):
            os.remove(make_path)


if __name__ == "__main__":
    main()
