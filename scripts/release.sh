#!/usr/bin/env bash
#
# Cuts a release tag and pushes it, or dispatches Publish Release for a tag that already
# exists, to every destination or only to the ones still to publish.
#
# Usage: scripts/release.sh <version> [--dry-run]
#        scripts/release.sh publish <version> <candidate-run-id>
#                           [--destinations <list>] [--attest <list>] [--dry-run]
#
#   scripts/release.sh 1.0.0
#   scripts/release.sh 1.0.0-beta.1
#   scripts/release.sh 0.9.1 --dry-run
#   scripts/release.sh publish 1.0.0 123456789 --attest modrinth,hangar
#   scripts/release.sh publish 1.0.0 123456789 --destinations hangar --attest hangar
#   scripts/release.sh publish 1.0.0 123456789 --destinations hangar-page --dry-run
#
# Pass the version without the leading "v"; the tag gets it. `--destinations` is a
# comma-separated subset of github,modrinth,hangar and defaults to all three, or
# hangar-page alone to sync an already-published version without an attestation. `--attest`
# names the registries whose owner inventory you have just inspected and found without
# this version; each is confirmed by typing the version. RELEASE_PROCESS.md says when an
# attestation is needed and which destination to select for a retry.
#
# Why this exists rather than `git tag && git push`: a tag is the only irreversible step
# in cutting a release. Publication is dispatched separately, and unpublishing from a
# registry is far more work than deleting a tag. The checks below are the accidents that
# are easy to have and expensive to undo - tagging from a linked worktree that is parked
# on an old commit, tagging a stale main, tagging with uncommitted work in the tree, or
# tagging a version whose changelog section does not exist.
#
# Everything here is also enforced in CI, except that CI only finds out after the tag is
# public. This finds out before.

set -euo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

fail() { echo "${RED}FAIL${RESET} $*" >&2; exit 1; }
ok()   { echo "${GREEN}ok${RESET}   $*"; }
warn() { echo "${YELLOW}warn${RESET} $*"; }

REPOSITORY=Ninja6-MC/SpiralGenesis
TAG_PATTERN='^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$'

usage() {
  echo "Usage: scripts/release.sh <version> [--dry-run]" >&2
  echo "       scripts/release.sh publish <version> <candidate-run-id> [--destinations <list>] [--attest <list>] [--dry-run]" >&2
  exit 2
}

# ---------------------------------------------------------------------------
# Dispatch Publish Release for a tag already on origin. Retrying one failed
# destination selects only it; a complete one is verified and skipped anyway.
# ---------------------------------------------------------------------------
publish() {
  [ $# -ge 2 ] || usage
  local version="$1" run_id="$2" destinations=github,modrinth,hangar attest="" dry_run=0
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --destinations) [ $# -ge 2 ] || usage; destinations="$2"; shift 2 ;;
      --attest) [ $# -ge 2 ] || usage; attest="$2"; shift 2 ;;
      --dry-run) dry_run=1; shift ;;
      *) fail "unknown option '$1'" ;;
    esac
  done
  local tag="v$version"
  [[ "$tag" =~ $TAG_PATTERN ]] || fail "'$version' is not a valid version."
  [[ "$run_id" =~ ^[1-9][0-9]*$ ]] || fail "'$run_id' is not a Verify Release Candidate run ID."

  local name seen="," modrinth=false hangar=false selected=() attested=()
  IFS=, read -r -a selected <<< "$destinations"
  [ "${#selected[@]}" -gt 0 ] || fail "no destination selected."
  for name in "${selected[@]}"; do
    case "$name" in github|modrinth|hangar|hangar-page) ;; *) fail "unknown destination '$name'." ;; esac
    if [[ "$seen" == *",$name,"* ]]; then fail "destination '$name' is listed twice."; fi
    seen="$seen$name,"
  done
  if [[ "$seen" == *",hangar-page,"* ]]; then
    [ "${#selected[@]}" -eq 1 ] || fail "hangar-page must be selected alone."
    [ -z "$attest" ] || fail "hangar-page takes no absence attestation."
  fi
  if [ -n "$attest" ]; then
    IFS=, read -r -a attested <<< "$attest"
  fi
  for name in ${attested[@]+"${attested[@]}"}; do
    case "$name" in modrinth|hangar) ;; *) fail "'$name' takes no absence attestation." ;; esac
    [[ "$seen" == *",$name,"* ]] || fail "'$name' is attested but not selected."
  done
  ok "publishing $tag to $destinations from candidate run $run_id"

  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 \
    || fail "tag '$tag' is not on origin. Cut it first: scripts/release.sh $version"
  ok "tag '$tag' exists on origin"

  for name in ${attested[@]+"${attested[@]}"}; do
    if [ "$dry_run" -eq 1 ]; then
      warn "dry run: not asking for the $name attestation"
    else
      echo
      echo "  Open the $name owner dashboard for SpiralGenesis and look for version $version,"
      echo "  including draft, scheduled, unlisted, hidden and deleted versions."
      read -r -p "  Type $version if no such version exists: " CONFIRM
      [ "$CONFIRM" = "$version" ] || fail "$name attestation not confirmed. Nothing was dispatched."
    fi
    case "$name" in modrinth) modrinth=true ;; hangar) hangar=true ;; esac
  done

  local nonce
  nonce="$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
  local args=(workflow run release.yml -R "$REPOSITORY" --ref "$tag" -f tag="$tag"
    -f candidate_run_id="$run_id" -f destinations="$destinations"
    -f modrinth_absence_confirmed="$modrinth" -f hangar_absence_confirmed="$hangar" -f nonce="$nonce")
  if [ "$dry_run" -eq 1 ]; then
    echo "${YELLOW}Dry run: nothing was dispatched.${RESET} The command would be:"
    echo "  gh ${args[*]}"
    exit 0
  fi
  gh "${args[@]}"
  ok "dispatched Publish Release [$nonce]"
  echo
  echo "Find the run by its nonce, then review it and approve the release environment in the browser:"
  echo "  gh run list -R $REPOSITORY --workflow release.yml --json databaseId,displayTitle --jq '.[] | select(.displayTitle | contains(\"$nonce\"))'"
  exit 0
}

if [ "${1:-}" = "publish" ]; then
  shift
  publish "$@"
fi

VERSION="${1:-}"
DRY_RUN=0
[ "${2:-}" = "--dry-run" ] && DRY_RUN=1

[ -n "$VERSION" ] || usage

TAG="v$VERSION"

# ---------------------------------------------------------------------------
# 1. The tag grammar CI enforces. Checked here so a typo costs nothing.
# ---------------------------------------------------------------------------
if [[ ! "$TAG" =~ $TAG_PATTERN ]]; then
  fail "'$VERSION' is not a valid version.
     Expected MAJOR.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N suffix.
     For example: 1.0.0, 1.0.0-beta.1"
fi
ok "version '$VERSION' is well formed"

# ---------------------------------------------------------------------------
# 2. Refuse to run from a linked worktree. Those sit on their own branches at
#    whatever commit they were created from, which is how a tag ends up pointing
#    at something several merges behind main.
# ---------------------------------------------------------------------------
GIT_DIR="$(git rev-parse --git-dir)"
COMMON_DIR="$(git rev-parse --git-common-dir)"
if [ "$GIT_DIR" != "$COMMON_DIR" ]; then
  fail "this is a linked worktree ($(git rev-parse --show-toplevel)).
     Release from the main checkout instead."
fi
ok "running in the main checkout"

# ---------------------------------------------------------------------------
# 3. On main, and nowhere else.
# ---------------------------------------------------------------------------
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || fail "on branch '$BRANCH', not 'main'. Run: git checkout main"
ok "on main"

# ---------------------------------------------------------------------------
# 4. Nothing uncommitted. A tag names a commit, so anything still in the working
#    tree is not in the release no matter what the diff looks like locally.
# ---------------------------------------------------------------------------
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo
  git status --short | sed 's/^/     /'
  echo
  fail "working tree has uncommitted changes."
fi
ok "working tree is clean"

UNTRACKED="$(git ls-files --others --exclude-standard | head -5)"
if [ -n "$UNTRACKED" ]; then
  warn "untracked files present (they will not be in the release):"
  echo "$UNTRACKED" | sed 's/^/       /'
fi

# ---------------------------------------------------------------------------
# 5. Exactly in step with origin/main. Behind means the release misses merged
#    work; ahead means it contains commits nobody has reviewed.
# ---------------------------------------------------------------------------
git fetch origin main --tags --quiet
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"

if [ "$LOCAL" != "$REMOTE" ]; then
  BEHIND="$(git rev-list --count HEAD..origin/main)"
  AHEAD="$(git rev-list --count origin/main..HEAD)"
  fail "main is not in step with origin/main ($AHEAD ahead, $BEHIND behind).
     Run: git pull origin main"
fi
ok "main matches origin/main at ${LOCAL:0:7}"

# ---------------------------------------------------------------------------
# 6. The tag must not already exist, locally or remotely. Re-tagging silently is
#    how a published version ends up pointing at different code than its tag.
# ---------------------------------------------------------------------------
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  fail "tag '$TAG' already exists locally. Delete it first: git tag -d $TAG"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  fail "tag '$TAG' already exists on origin. That version has been released."
fi
ok "tag '$TAG' is unused"

# ---------------------------------------------------------------------------
# 7. The changelog gate CI applies, applied here first. Stable releases must have
#    a section; pre-releases fall back to their base version and may go without.
# ---------------------------------------------------------------------------
BASE_VERSION="${VERSION%%-*}"
PRERELEASE=0
[ "$VERSION" != "$BASE_VERSION" ] && PRERELEASE=1

extract_notes() {
  awk -v header="## [$1]" '
    index($0, header) == 1 { found = 1; next }
    found && /^## / { exit }
    found { print }
  ' CHANGELOG.md
}

NOTES="$(extract_notes "$VERSION")"
[ -z "${NOTES//[[:space:]]/}" ] && NOTES="$(extract_notes "$BASE_VERSION")"

if [ -z "${NOTES//[[:space:]]/}" ]; then
  if [ "$PRERELEASE" -eq 0 ]; then
    fail "CHANGELOG.md has no '## [$BASE_VERSION]' section.
     A stable release must document what changed. Move the [Unreleased] entries
     under that heading, merge to main, and try again."
  fi
  warn "no changelog section for $VERSION; the release notes will point at GitHub"
else
  ok "changelog section found for $BASE_VERSION"
fi

# ---------------------------------------------------------------------------
# 8. Show what is about to happen, then require it to be typed out. Everything
#    above this line is reversible; everything below is not.
# ---------------------------------------------------------------------------
if [[ "$TAG" =~ alpha ]]; then
  CHANNEL="alpha (GitHub pre-release, Modrinth alpha, Hangar Alpha)"
elif [[ "$TAG" =~ beta|rc ]]; then
  CHANNEL="beta (GitHub pre-release, Modrinth beta, Hangar Beta)"
else
  CHANNEL="release (GitHub latest, Modrinth release, Hangar Release)"
fi

echo
echo "  tag      $TAG"
echo "  commit   $(git log -1 --format='%h %s')"
echo "  channel  $CHANNEL"
echo
echo "  release notes:"
if [ -n "${NOTES//[[:space:]]/}" ]; then
  printf '%s\n' "$NOTES" | grep -v '^[[:space:]]*$' | head -8 | sed 's/^/    /'
  TOTAL="$(printf '%s\n' "$NOTES" | grep -cv '^[[:space:]]*$')"
  [ "$TOTAL" -gt 8 ] && echo "    ... and $((TOTAL - 8)) more lines"
else
  echo "    (none)"
fi
echo

if [ "$DRY_RUN" -eq 1 ]; then
  echo "${YELLOW}Dry run: every check passed. Nothing was tagged or pushed.${RESET}"
  exit 0
fi

echo "Push this tag only after a successful Verify Release Candidate run for"
echo "this commit and tag. Tag push does not publish; promotion is dispatched"
echo "separately with that run ID."
echo
read -r -p "Type the tag to confirm: " CONFIRM
[ "$CONFIRM" = "$TAG" ] || fail "confirmation did not match. Nothing was tagged."

# ---------------------------------------------------------------------------
# 9. Tag, then push. The tag is created locally first so that a failed push
#    leaves something to inspect and delete rather than a half-published state.
# ---------------------------------------------------------------------------
git tag -a "$TAG" -m "SpiralGenesis $TAG"
ok "created $TAG at ${LOCAL:0:7}"

git push origin "$TAG"
ok "pushed $TAG"

echo
echo "Publish it once you have inspected each registry's owner inventory for $VERSION:"
echo "  scripts/release.sh publish $VERSION <candidate-run-id> --attest modrinth,hangar"
echo "See RELEASE_PROCESS.md for attestation, verification and retry rules."
