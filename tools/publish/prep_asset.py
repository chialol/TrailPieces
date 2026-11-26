#!/usr/bin/env python3
"""Build an assets/<package> workspace from reveal folders.

The source folder is copied, not removed. Run move again with another
shared/source/reveal/<id> folder and the same package name to add it.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import argparse

from trailpublish.errors import PublishError
from trailpublish.prep_asset import add_reveal, resolve_photo_folder


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Copy a reveal folder into assets/<package>.")
    sub = parser.add_subparsers(dest="command", required=True)
    move = sub.add_parser("move", help="Copy one reveal folder into a package, creating the package if needed.")
    move.add_argument("photo_folder", help="Path to shared/source/reveal/<id>, relative to the repo or the current directory.")
    move.add_argument("package_name", help="Package id, for example cedar. Created under assets/.")
    args = parser.parse_args(argv)
    try:
        if args.command != "move":
            parser.error(f"Unknown command {args.command}")
        report = add_reveal(resolve_photo_folder(args.photo_folder), args.package_name)
    except PublishError as error:
        print(f"prep_asset.py: {error}", file=sys.stderr)
        return 1
    print(f"Added {report.photo_id} to {report.package_dir}.")
    print(f"Scene: {report.scene_dir}")
    if report.seeded_from_meta:
        print("Filled empty wording from meta.json. Reviewed text already in the package was left as it is.")
    if report.review_ids:
        print("Photos that still need review: " + ", ".join(report.review_ids))
    print("The source folder was left in place.")
    print("Next, from the repo:")
    print(f"  .\\make publish validate {report.package_id}")
    print(f"  .\\make publish approve {report.package_id}")
    print(f"  .\\make publish publish {report.package_id} --dry-run")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
