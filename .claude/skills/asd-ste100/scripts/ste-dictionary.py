#!/usr/bin/env python3
"""Extract the ASD-STE100 word list from a local copy of the standard.

Reads references/ASD-STE100_ISSUE9.pdf and writes references/ste-dictionary.local.json,
which ste-lint.py reads for its unapproved-word rule. Both files are ASD's copyright and
stay out of git. This script holds no ASD text, so it can be committed.

Needs pypdf and cryptography (the PDF is AES-encrypted with an empty user password):
    py -3 -m pip install --target <dir> pypdf cryptography
    PYTHONPATH=<dir> py -3 scripts/ste-dictionary.py

Output: {"approved": {word: [pos]}, "restricted": [word],
         "unapproved": {word: {pos: [alternatives]}}}
A restricted word is approved in one sense only: its entry says "For other meanings, use:".
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
PDF = HERE / "references" / "ASD-STE100_ISSUE9.pdf"
OUT = HERE / "references" / "ste-dictionary.local.json"

POS = r"n|v|adj|adv|prep|conj|pron|art"
# Column 1 of an entry: a headword and its part of speech at the start of a line.
# Lowercase means not approved, uppercase means approved (Part 2, Introduction).
HEAD = re.compile(r"^([A-Za-z][A-Za-z'.\- ]*?) \((" + POS + r")\)(.*)$")
# An approved alternative in column 2: uppercase words and their part of speech.
ALT = re.compile(r"([A-Z][A-Z'\- ]*?) \((" + POS + r"|TN|TV)\)")
# A headword whose part of speech wrapped onto the next line, as "simultaneously" / "(adv)".
BARE = re.compile(r"^([A-Za-z][A-Za-z'\- ]*?)\s*$")
WRAPPED_POS = re.compile(r"^\((" + POS + r")\)(.*)$")
# A further alternative for the same headword starts a line with whitespace.
CONT = re.compile(r"^\s+([A-Z][A-Z'\- ]*?) \((" + POS + r"|TN|TV)\)")


def dictionary_pages(reader):
    """Part 2's word list starts at page 2-1-A1 and runs to the end."""
    started = False
    for page in reader.pages:
        text = page.extract_text() or ""
        if not started and "Page 2-1-A1" in text:
            started = True
        if started:
            yield text


def parse(pages):
    approved, unapproved, restricted = {}, {}, set()
    current = None
    last_approved = None
    pending = None
    for text in pages:
        for line in text.splitlines():
            if line.startswith(("ASD", "Word ", "(part of speech)", "Issue 9", "Page ", "2025-")):
                continue
            if line.startswith("For other") and last_approved:
                restricted.add(last_approved)
                continue
            wrapped = WRAPPED_POS.match(line.strip())
            if wrapped and pending:
                line = f"{pending} ({wrapped.group(1)}){wrapped.group(2)}"
            bare = BARE.match(line)
            pending = bare.group(1).strip() if bare else None
            head = HEAD.match(line)
            if head:
                word, pos, rest = head.group(1).strip(), head.group(2), head.group(3)
                if word.isupper() or word[0].isupper() and word.upper() == word:
                    approved.setdefault(word.lower(), [])
                    if pos not in approved[word.lower()]:
                        approved[word.lower()].append(pos)
                    current = None
                    last_approved = word.lower()
                elif word.islower():
                    last_approved = None
                    alts = unapproved.setdefault(word, {}).setdefault(pos, [])
                    alt = ALT.match(rest.strip())
                    if alt:
                        alts.append(f"{alt.group(1).strip().lower()} ({alt.group(2)})")
                    current = alts
                else:
                    current = None
                    last_approved = None
                continue
            cont = CONT.match(line)
            if cont and current is not None:
                alt = f"{cont.group(1).strip().lower()} ({cont.group(2)})"
                if alt not in current:
                    current.append(alt)
    return approved, unapproved, sorted(restricted)


def main():
    try:
        import pypdf
    except ImportError:
        sys.exit("pypdf is not installed. See this script's docstring.")
    if not PDF.exists():
        sys.exit(f"No standard at {PDF}. Request it from https://www.asd-ste100.org/STE_downloads.html")
    approved, unapproved, restricted = parse(dictionary_pages(pypdf.PdfReader(str(PDF))))
    OUT.write_text(json.dumps({"approved": approved, "restricted": restricted,
                               "unapproved": unapproved},
                              indent=1, sort_keys=True), encoding="utf-8")
    print(f"{len(approved)} approved ({len(restricted)} in one sense only) and "
          f"{len(unapproved)} unapproved headwords -> {OUT}")


if __name__ == "__main__":
    main()
