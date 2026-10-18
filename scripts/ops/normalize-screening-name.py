#!/usr/bin/env python3
"""Normalises names for the screening lists exactly as the service matches them.

Rule (the same as NormalizedName in compliance-domain): canonical
decomposition (NFD) with every combining mark removed, upper case, every
character that is not a letter or digit replaced by a space, whitespace
collapsed and trimmed. Letters without a decomposition (for example a stroked
L) are kept. The two implementations are kept in step by the shared fixtures
scripts/ops/test/screening-name-fixtures.csv: NormalizedNameTest checks the
Java side, the CI loader rehearsal this side. Change both and the fixtures
together.

Usage: normalize-screening-name.py <names.csv> <staged.csv>
  names.csv   one column, header exactly "display_name"
  staged.csv  written: display_name,normalized_name

Exit 1 with a message on a wrong header, an empty file or a name that
normalises to nothing (nothing is written then).
"""
import csv
import sys
import unicodedata


def normalize(raw: str) -> str:
    decomposed = unicodedata.normalize("NFD", raw)
    letters = "".join(c for c in decomposed if not unicodedata.category(c).startswith("M"))
    upper = letters.upper()
    spaced = "".join(c if c.isalnum() else " " for c in upper)
    return " ".join(spaced.split())


def main(argv):
    if len(argv) != 3:
        print("usage: normalize-screening-name.py <names.csv> <staged.csv>", file=sys.stderr)
        return 2
    source, target = argv[1], argv[2]
    with open(source, encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader, None)
        if header != ["display_name"]:
            print(f"refused: the first line must be the header 'display_name', got {header!r}", file=sys.stderr)
            return 1
        rows = []
        for line_number, row in enumerate(reader, start=2):
            if not row or (len(row) == 1 and row[0].strip() == ""):
                continue
            if len(row) != 1:
                print(f"refused: line {line_number} has {len(row)} columns, expected one", file=sys.stderr)
                return 1
            display = row[0].strip()
            normalized = normalize(display)
            if not normalized:
                print(f"refused: line {line_number} normalises to nothing (no letters or digits)", file=sys.stderr)
                return 1
            rows.append((display, normalized))
    if not rows:
        print("refused: the file has no names below the header", file=sys.stderr)
        return 1
    with open(target, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(["display_name", "normalized_name"])
        writer.writerows(rows)
    print(len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
