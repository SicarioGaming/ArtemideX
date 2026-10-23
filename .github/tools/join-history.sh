#!/usr/bin/env bash
# One-time switch of the public repo to the real history, with no force push.
#
# Until 0.2.0 the public repo got one commit per top-level folder for each release, which hid what
# changed and shares no history with Moonlight X. Private main has the real history and exactly the
# same files as public main. This builds one merge commit whose files are main's, whose first parent is
# private main and whose second parent is public main. Public main can then fast-forward to it: clones
# and forks keep working, the release tags stay where they are, and upstream merges work again.
#
#   bash .github/tools/join-history.sh
#
# It never pushes. It builds the commit with plumbing (no working tree or index is touched), writes it
# to the local branch history-join, checks it, and prints the push commands for you to run yourself.
# Run it again with the same JOIN_DATE and it builds the identical commit.
#
# Settings (environment):
#   JOIN_DATE     Unix seconds for the commit date (default: now). Printed, so a re-run can reuse it.
#   FETCH=0       don't fetch public main and origin main first (default 1: fetch, which only reads)
#   PATTERNS      patterns file for check-publish.sh (default private/privacy-patterns.txt)
#   PUBLIC_TIP, PRIVATE_TIP   the commits this was planned against; the script stops if either moved.
#                 If private main gets rewritten first, set PRIVATE_TIP to the new tip, and
#                 ALLOW_TREE_DIFF=1 if its files then differ a little from public main's.
set -euo pipefail

stop() { echo "stopped: $*" >&2; exit 1; }
[ -z "${PUSH:-}" ] || stop "this script never pushes; it prints the push commands for you to run"

here=$(cd "$(dirname "$0")" && pwd)
if [ "$(git rev-parse --is-bare-repository)" = false ]; then cd "$(git rev-parse --show-toplevel)"; fi

export GIT_AUTHOR_NAME=SicarioGaming GIT_COMMITTER_NAME=SicarioGaming
export GIT_AUTHOR_EMAIL=stayf8dop@gmail.com GIT_COMMITTER_EMAIL=stayf8dop@gmail.com
JOIN_DATE=${JOIN_DATE:-$(date -u +%s)}
case "$JOIN_DATE" in ''|*[!0-9]*) stop "JOIN_DATE must be Unix seconds" ;; esac
export GIT_AUTHOR_DATE="$JOIN_DATE +0000" GIT_COMMITTER_DATE="$JOIN_DATE +0000"

PUBLIC_TIP=${PUBLIC_TIP:-2fd83951e379bda63ed727a182e4b1cf7fa58c71}    # public main on 23 Oct 2026
PRIVATE_TIP=${PRIVATE_TIP:-ce44af755a69c496dc969a429a664818b472acf3}  # private main, same files
UPSTREAM_BASE=7c8209cf705ad1bf7d491b4835274bfb7c101626  # Moonlight X's mlsoft-x when Starwire forked
UPSTREAM_REF=${UPSTREAM_REF:-refs/remotes/moonlightx/mlsoft-x}
RELEASES="v0.1.0=2d0c6f33609f023a67d338776c178e281779bbe3 v0.1.2=da1b8d5690b773ec6c30878c76edd3dbd6a055a4 v0.2.0=9184d1faa33a197169fc53dde025cd8557fb5d9b"
# The same app code in the real history, for the message
REAL_RELEASES="v0.1.0=96f1058f v0.1.2=6288a150 v0.2.0=1a61c912"
JOIN_REF=refs/heads/history-join
PATTERNS=${PATTERNS:-private/privacy-patterns.txt}

if [ "${FETCH:-1}" = 1 ]; then
  git fetch --no-tags public main
  git fetch --no-tags origin main
fi
[ "$(git rev-parse refs/remotes/public/main)" = "$PUBLIC_TIP" ] || stop "public main moved since this was planned; re-plan"
[ "$(git rev-parse refs/heads/main)" = "$PRIVATE_TIP" ] || stop "private main moved since this was planned; re-plan"
[ "$(git rev-parse refs/remotes/origin/main)" = "$PRIVATE_TIP" ] || stop "origin/main differs from main"
if [ "${ALLOW_TREE_DIFF:-0}" != 1 ]; then
  git diff --quiet refs/heads/main refs/remotes/public/main || stop "files differ between private and public main"
fi

msg=$(mktemp)
trap 'rm -f "$msg"' EXIT
{
  cat <<'MSG'
Publish the full commit history

Until now this repo had one commit per top-level folder for each release, which hid what changed
and cut it off from Moonlight X, so upstream couldn't be merged. This merge joins that history to
the real one: Moonlight's and Moonlight X's commits with their original authors, then every
Starwire commit. No file changes.

The release tags stay where they were. The same app code is in these commits of the real history:
MSG
  for r in $REAL_RELEASES; do echo "- ${r%%=*}: ${r#*=}"; done
} > "$msg"

join=$(git commit-tree "refs/heads/main^{tree}" -p refs/heads/main -p refs/remotes/public/main -F "$msg")
old=$(git rev-parse -q --verify "$JOIN_REF" || true)
git update-ref -m "join-history.sh" "$JOIN_REF" "$join"
echo "JOIN_DATE=$JOIN_DATE"
echo "join commit: $join (local branch ${JOIN_REF#refs/heads/})"
[ -z "$old" ] || [ "$old" = "$join" ] || echo "note: history-join was $old before"

# Checks
fail=0
if git merge-base --is-ancestor refs/remotes/public/main "$join"; then echo "ok: public main fast-forwards to it"
else echo "FAIL: not a fast-forward from public main"; fail=1; fi
if git merge-base --is-ancestor refs/remotes/origin/main "$join"; then echo "ok: private origin main fast-forwards to it"
else echo "FAIL: not a fast-forward from origin main"; fail=1; fi
if git diff --quiet refs/remotes/public/main "$join"; then echo "ok: same files as public main"
else echo "note: files differ from public main:"; git diff --stat refs/remotes/public/main "$join" | tail -n 1; fi
if git rev-parse -q --verify "$UPSTREAM_REF" >/dev/null; then
  base=$(git merge-base "$join" "$UPSTREAM_REF" || true)
  if [ "$base" = "$UPSTREAM_BASE" ]; then echo "ok: merge base with Moonlight X ($UPSTREAM_REF) is ${base:0:8}"
  else echo "FAIL: merge base with $UPSTREAM_REF is ${base:-none}, expected ${UPSTREAM_BASE:0:8}"; fail=1; fi
else
  echo "note: no $UPSTREAM_REF here, so the Moonlight X check was skipped"
fi
for r in $RELEASES; do
  if git merge-base --is-ancestor "${r#*=}" "$join"; then echo "ok: ${r%%=*} (${r#*=}) is reachable"
  else echo "FAIL: ${r%%=*} (${r#*=}) is not reachable"; fail=1; fi
done
echo "commits: $(git rev-list --count refs/remotes/public/main) on public main now, $(git rev-list --count "$join") after"
if du_old=$(git rev-list --objects --disk-usage refs/remotes/public/main 2>/dev/null) &&
   du_new=$(git rev-list --objects --disk-usage "$join" 2>/dev/null); then
  echo "clone size estimate (objects as packed here): $((du_old / 1048576)) MiB now, $((du_new / 1048576)) MiB after"
fi

echo "== check-publish.sh"
if ! bash "$here/check-publish.sh" refs/remotes/public/main "$join" "$PATTERNS"; then fail=1; fi

if [ $fail != 0 ]; then
  echo
  echo "Not ready: fix or accept what's listed above (accept a known hit with an \"allow <commit> <path>\""
  echo "line in $PATTERNS), then run this again with JOIN_DATE=$JOIN_DATE."
  exit 1
fi

cat <<EOF

Ready. Nothing has been pushed. To publish, run these yourself:

  1. Look at it on GitHub first (a branch only, main doesn't change):
       git push public history-join:refs/heads/history-preview

  2. When it looks right, move both mains to it. Each is a plain push, which GitHub refuses
     unless it's a fast-forward:
       git push origin history-join:main
       git push public history-join:main
       git update-ref refs/heads/main $join $PRIVATE_TIP
       git push public --delete history-preview
       git branch -D history-join

  If main is checked out, run "git merge --ff-only history-join" on it instead of update-ref.
EOF
