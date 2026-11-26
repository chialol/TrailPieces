from __future__ import annotations

import argparse
import sys
from pathlib import Path

from trailpublish.config import load_config, resolve_config_path
from trailpublish.errors import PublishError
from trailpublish.model import parse_unlock_arg
from trailpublish.publishing import list_packages, publish_package, rollback_package
from trailpublish.store import open_s3_store
from trailpublish.validate import local_issues
from trailpublish.workspace import PrepareOptions, approve, import_review, prepare


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="publish.py",
        description="Prepare, review, and publish Trail Pieces content packages.",
    )
    parser.add_argument("--config", help="Path to publish.config.json")
    sub = parser.add_subparsers(dest="command", required=True)

    prepare_cmd = sub.add_parser("prepare", help="Scan photographs and write a review workspace.")
    prepare_cmd.add_argument("folder")
    prepare_cmd.add_argument("--package-id")
    prepare_cmd.add_argument("--title")
    prepare_cmd.add_argument("--description")
    prepare_cmd.add_argument("--unlock", help="immediate, puzzles:<count>, or package:<id>")
    prepare_cmd.add_argument("--access", default="public")
    prepare_cmd.add_argument("--cover-photo")
    prepare_cmd.add_argument("--order", type=int)
    prepare_cmd.add_argument("--min-app-version")
    prepare_cmd.add_argument("--all", action="store_true", help="Regenerate review images for every photo.")
    prepare_cmd.add_argument("--non-interactive", action="store_true")

    validate_cmd = sub.add_parser("validate", help="Check a package workspace.")
    validate_cmd.add_argument("folder")

    approve_cmd = sub.add_parser("approve", help="Record editorial approval of the current metadata and sources.")
    approve_cmd.add_argument("folder")

    import_cmd = sub.add_parser("import-review", help="Merge reviewed metadata into the workspace by photo id.")
    import_cmd.add_argument("folder")
    import_cmd.add_argument("reviewed")

    publish_cmd = sub.add_parser("publish", help="Upload a package and update the catalog last.")
    publish_cmd.add_argument("folder")
    publish_cmd.add_argument("--dry-run", action="store_true")
    publish_cmd.add_argument("--yes", action="store_true", help="Publish without a confirmation prompt.")
    publish_cmd.add_argument("--allow-removals", action="store_true")
    publish_cmd.add_argument("--allow-identity-change", action="store_true")
    publish_cmd.add_argument("--share-bundled-id", action="store_true")
    publish_cmd.add_argument("--invalidate", action="store_true", help="Invalidate only the catalog path.")

    sub.add_parser("list", help="List packages in the remote catalog.")

    rollback_cmd = sub.add_parser("rollback", help="Point the catalog at an older package release.")
    rollback_cmd.add_argument("package_id")
    rollback_cmd.add_argument("version")
    rollback_cmd.add_argument("--dry-run", action="store_true")
    rollback_cmd.add_argument("--yes", action="store_true")
    rollback_cmd.add_argument("--invalidate", action="store_true")

    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            return _prepare(args)
        if args.command == "validate":
            return _validate(Path(args.folder))
        if args.command == "approve":
            return _approve(Path(args.folder))
        if args.command == "import-review":
            notes = import_review(Path(args.folder), Path(args.reviewed))
            print("Merged reviewed metadata by photo id.")
            for note in notes:
                print(note)
            return 0
        config = load_config(resolve_config_path(args.config))
        store = open_s3_store(config)
        if args.command == "publish":
            return _publish(args, store, config)
        if args.command == "list":
            return _list(store, config)
        if args.command == "rollback":
            return _rollback(args, store, config)
    except PublishError as error:
        print(f"publish.py: {error}", file=sys.stderr)
        return 1
    parser.error(f"Unknown command {args.command}")
    return 2


def _prepare(args) -> int:
    folder = Path(args.folder)
    interactive = not args.non_interactive and sys.stdin.isatty()
    existing = folder / "package.json"
    package_id = args.package_id or ""
    title = args.title or ""
    description = args.description or ""
    unlock_text = args.unlock or ""
    if not existing.exists():
        package_id = package_id or _ask("Package id", interactive)
        title = title or _ask("Title", interactive)
        description = description or _ask("Description", interactive)
        unlock_text = unlock_text or _ask("Unlock (immediate, puzzles:3, package:id)", interactive, default="immediate")
    elif interactive and not unlock_text and args.title is None and args.description is None:
        pass
    unlock = parse_unlock_arg(unlock_text or "immediate")
    set_details = any(value is not None for value in (args.title, args.description, args.unlock, args.order, args.min_app_version))
    if not existing.exists():
        set_details = True
    report = prepare(
        folder,
        PrepareOptions(
            package_id=package_id,
            title=title,
            description=description,
            access=args.access,
            unlock=unlock,
            cover_photo_id=args.cover_photo,
            order=0 if args.order is None else args.order,
            min_app_version=args.min_app_version,
            review_all=args.all,
            set_details=set_details or not existing.exists(),
        ),
    )
    print(f"Prepared {report.package_id} with {len(report.photo_ids)} photos.")
    if report.review_ids:
        print("Review images: " + ", ".join(report.review_ids))
        print(f"Upload {report.folder / 'review'} with the photographs' review images to ChatGPT.")
    else:
        print("No photographs need a new review pass.")
    for warning in report.warnings:
        print(f"warning: {warning}")
    return 0


def _approve(folder: Path) -> int:
    issues = local_issues(folder)
    errors = [issue for issue in issues if issue.level == "error"]
    for issue in issues:
        if issue.level != "error":
            print(f"{issue.level}: {issue.message}")
    if errors:
        print("Fix these before approving:", file=sys.stderr)
        for issue in errors:
            print(f"- {issue.message}", file=sys.stderr)
        return 1
    approve(folder)
    notices = [issue for issue in issues if issue.level == "notice"]
    if notices:
        print("Approved the metadata, including the new classifications and trail revisions above.")
    else:
        print("Approved the current metadata and source images.")
    return 0


def _validate(folder: Path) -> int:
    issues = local_issues(folder)
    errors = [issue for issue in issues if issue.level == "error"]
    for issue in issues:
        print(f"{issue.level}: {issue.message}")
    if errors:
        return 1
    if any(issue.level == "notice" for issue in issues):
        print("Valid. Approve to accept the additions above.")
        return 0
    print("Valid.")
    return 0


def _publish(args, store, config) -> int:
    folder = Path(args.folder)
    report = publish_package(
        folder,
        store,
        config,
        dry_run=True,
        allow_removals=args.allow_removals,
        allow_identity_change=args.allow_identity_change,
        share_bundled=args.share_bundled_id,
    )
    print(report.text())
    if args.dry_run or report.unchanged:
        return 0
    if not args.yes and not _confirm("Publish this package?"):
        print("Cancelled.")
        return 1
    final = publish_package(
        folder,
        store,
        config,
        dry_run=False,
        confirmed=True,
        allow_removals=args.allow_removals,
        allow_identity_change=args.allow_identity_change,
        share_bundled=args.share_bundled_id,
        invalidate=args.invalidate or config.invalidate_catalog,
    )
    print(final.text())
    return 0


def _rollback(args, store, config) -> int:
    report = rollback_package(
        store,
        config,
        args.package_id,
        args.version,
        dry_run=True,
    )
    print(report.text())
    if args.dry_run or report.unchanged:
        return 0
    if not args.yes and not _confirm("Update the catalog to this release?"):
        print("Cancelled.")
        return 1
    final = rollback_package(
        store,
        config,
        args.package_id,
        args.version,
        confirmed=True,
        invalidate=args.invalidate or config.invalidate_catalog,
    )
    print(final.text())
    return 0


def _list(store, config) -> int:
    packages = list_packages(store, config)
    if not packages:
        print("No packages in the catalog.")
        return 0
    for package in packages:
        print(
            f"{package.get('id')}  release {package.get('release')}  "
            f"{package.get('photoCount', '?')} photos  {package.get('title')}"
        )
    return 0


def _ask(label: str, interactive: bool, default: str = "") -> str:
    if not interactive:
        if default:
            return default
        raise PublishError(f"Pass --{label.split()[0].lower().replace(' ', '-')} or run in a terminal.")
    suffix = f" [{default}]" if default else ""
    answer = input(f"{label}{suffix}: ").strip()
    return answer or default


def _confirm(message: str) -> bool:
    if not sys.stdin.isatty():
        raise PublishError(f"{message} Re-run with --yes to confirm.")
    answer = input(f"{message} [y/N] ").strip().lower()
    return answer in {"y", "yes"}
