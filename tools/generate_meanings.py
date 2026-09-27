#!/usr/bin/env python3
"""
Generate gzipped JSON word-meaning shards from WordNet 3.0 MIT dictionary files.

Input files (download separately from https://wordnet.princeton.edu):
  data.noun, data.verb, data.adj, data.adv

Output structure:
  assets/meanings/a.json.gz
  assets/meanings/b.json.gz
  ...
  assets/meanings/z.json.gz

Each file contains a JSON object mapping lowercase words to arrays of senses:
  { "word": "ephemeral" } => [
    { "pos": "n", "definition": "lasting a very short time" },
    { "pos": "v", "definition": "to vanish quickly" }
  ]

Only keeps the first definition per (word, pos) pair to keep shard sizes small.
"""

import gzip
import json
import os
import re
import sys
from pathlib import Path

INPUT_DIR = Path(__file__).parent.parent / "data_files"
OUTPUT_DIR = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "meanings"

# Patterns used in WordNet dict files
# Noun:   #n   0   definition\n  (senses numbered)
# Verb:   #v   0   definition\n  (same pattern)
# Adj:    #a   0   definition\n
# Adv:    #r   0   definition\n
POS_MAP = {
    "#n": "n",  # noun
    "#v": "v",  # verb
    "#a": "a",  # adjective
    "#r": "adv", # adverb
}


def parse_file(path: Path, pos_tag: str):
    """Parse one WordNet data.* file, yielding (lowercase_word, pos, definition)."""
    pos_label = POS_MAP[pos_tag]
    current_pos = None
    current_synset_num = None
    entries_seen = set()  # track (word, pos) to deduplicate across synsets

    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")

            # Synset header like:  verb.motion.01.01
            if re.match(r"\w+\.\w+\.\d\d\.\d\d$", line):
                current_pos = line.split(".")[1]  # noun, verb, adj, adv
                continue

            # Definition line: starts with a tab, ends with \t
            # Format: \tdefinition text\t
            if line.startswith("\t") and line.endswith("\t"):
                defn = line.strip("\t").strip()
                if not defn or len(defn) > 200:
                    continue

                # Extract words from this synset definition
                # Words appear before the pos tag in the lex_filenum field
                # Actually we need to parse the lexicon entry lines, not defs
                continue

            # Lexical entry line: word\tidx\tfreq\t...
            parts = line.split("\t")
            if len(parts) < 3:
                continue

            word_raw = parts[0].strip().lower()
            if not word_raw or len(word_raw) > 40:
                continue

            # Determine pos from current context
            # The file is organized by pos, so use the section header
            if pos_tag == "#n" and "noun" in line.lower():
                current_pos = "n"
            elif pos_tag == "#v" and "verb" in line.lower():
                current_pos = "v"
            elif pos_tag == "#a" and "adj" in line.lower():
                current_pos = "a"
            elif pos_tag == "#r" and "adv" in line.lower():
                current_pos = "adv"

            if current_pos is None:
                continue

            # Skip multi-word phrases (keep single words only)
            if " " in word_raw or "-" in word_raw or "'" in word_raw:
                continue

            # Only ASCII letters and digits
            if not re.match(r"^[a-z][a-z0-9]*$", word_raw):
                continue

            key = (word_raw, current_pos)
            if key in entries_seen:
                continue
            entries_seen.add(key)

            yield word_raw, current_pos, defn


def main():
    if not INPUT_DIR.exists():
        print(f"ERROR: Input directory not found: {INPUT_DIR}", file=sys.stderr)
        print("Download WordNet data files from https://wordnet.princeton.edu", file=sys.stderr)
        print("Place them in:", INPUT_DIR, file=sys.stderr)
        sys.exit(1)

    pos_files = {
        "#n": INPUT_DIR / "data.noun",
        "#v": INPUT_DIR / "data.verb",
        "#a": INPUT_DIR / "data.adj",
        "#r": INPUT_DIR / "data.adv",
    }

    all_entries = {}  # word -> [(pos, definition)]

    for pos_tag, fpath in pos_files.items():
        if not fpath.exists():
            print(f"WARNING: Missing file {fpath}, skipping", file=sys.stderr)
            continue
        count = 0
        for word, pos, defn in parse_file(fpath, pos_tag):
            all_entries.setdefault(word, []).append({"pos": pos, "definition": defn})
            count += 1
        print(f"Parsed {count} entries from {fpath.name}")

    # Limit each word to at most 8 senses to control size
    for word in all_entries:
        all_entries[word] = all_entries[word][:8]

    # Group by first letter and write shards
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    total_words = 0

    for letter in "abcdefghijklmnopqrstuvwxyz":
        shard = {k: v for k, v in all_entries.items() if k[0] == letter}
        if not shard:
            continue
        data = json.dumps(shard, ensure_ascii=False).encode("utf-8")
        out_path = OUTPUT_DIR / f"{letter}.json.gz"
        with gzip.open(out_path, "wb", compresslevel=9) as f:
            f.write(data)
        size_kb = out_path.stat().st_size / 1024
        total_words += len(shard)
        print(f"  {letter}.json.gz: {size_kb:.0f} KB ({len(shard)} words)")

    total_gz = sum(p.stat().st_size for p in OUTPUT_DIR.glob("*.json.gz"))
    print(f"\nTotal: {total_words} unique words, {total_gz / 1024 / 1024:.1f} MB compressed")
    print(f"Output: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
