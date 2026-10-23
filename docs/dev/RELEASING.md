# Releasing Starwire (proposal)

**Status: a proposal, not in use yet.** Nothing here changes how 0.2.0 or anything before it was
published. It needs my go-ahead first, mainly for the one-time history switch below, because that
puts about 3,500 commits on the public repo and can't really be taken back. Until then
`private/publish-history.sh` stays where it is.

## Why change

Up to 0.2.0 the public repo got its commits from `private/publish-history.sh`: one commit per
top-level folder for each release, with the same 13 fixed messages every time. That made the
history impossible to follow, and it shares no commits with Moonlight X, so `git merge` from
upstream says "refusing to merge unrelated histories". The real history was never lost: the
private repo has every Moonlight and Moonlight X commit with its original author, then every
Starwire commit with a proper message.

The new flow publishes that real history and keeps it: both repos have the same `main`, releases
are plain tags on it, and a check runs before anything goes public.

## Once: switch the public repo to the real history

Private `main` has exactly the same files as public `main`. `.github/tools/join-history.sh` builds
one merge commit with those files, private `main` as its first parent and public `main` as its
second. Public `main` then moves forward to it with an ordinary push:

- no force push, so clones and the two forks keep working (`git pull`, or **Sync fork**)
- the release tags v0.1.0, v0.1.2 and v0.2.0 and their release pages stay exactly as they are
- the old per-folder commits are still there, as the merge's second parent
- `git merge moonlightx/mlsoft-x` works again

The script never pushes. It writes the commit to a local branch, `history-join`, checks it, runs
the pre-publish check and prints the push commands to run by hand: first to a `history-preview`
branch on the public repo so I can look at it on GitHub, then to both `main`s.

```
bash .github/tools/join-history.sh
```

Dry run in a scratch clone (23 Oct): public `main` fast-forwards to the join, its merge base with
Moonlight X is `7c8209cf` (Moonlight X's tip when Starwire forked), all three release tags are
reachable, and a trial merge of the latest Moonlight X gives 17 files to resolve instead of
"unrelated histories". A fresh clone grows from about 22 MiB to about 156 MiB (`--depth 1` avoids
that).

Before running it, decide:

- **The check's hits.** On the dry run the pre-publish check stopped on 6 commits of the real
  history that were never public. Either accept them (add `allow` lines for those commits to the
  patterns file, see below) or rewrite those commits first, which means force-pushing the private
  repo once and rebasing the work branches.
- **What else becomes public:** the old names ArtemideX and Voidlink, draft release notes, and the
  `Co-Authored-By: Claude` lines on about 100 commits.

## The pre-publish check

`.github/tools/check-publish.sh` looks at every commit a push would add to the public repo and stops
if it finds:

- a commit or tag by anyone other than `SicarioGaming <stayf8dop@gmail.com>`, or not dated in UTC
- a keystore or key file, `keystore.properties`, `local.properties`, `.env`, anything under
  `private/`, or a Reddit draft
- an access token, a private key, a written-out password, a local user folder, or a private network
  address outside the test files, in an added line, a file name or a commit message
- anything matching `private/privacy-patterns.txt`: my real name and personal accounts, the PC's
  name, addresses and folder layout, and the test devices' serials. That file is in `private/`,
  which git ignores, so the patterns are never published.
- placeholder words left in the README, docs or release notes

It reads the repo only, needs no network, gives the same answer every time and runs in a few
seconds. Its output says where a hit is (commit, file and line), never what matched, so it's safe to
paste. A hit I've decided to accept can be listed as `allow <commit> <path>` in the patterns file.

Run it by hand:

```
bash .github/tools/check-publish.sh public/main main
```

Or install it as a pre-push hook, once per clone:

```
printf '%s\n' '#!/bin/sh' 'f=.github/tools/check-publish.sh' 'if [ -f "$f" ]; then exec bash "$f" --pre-push "$@"; fi' 'case "$1 $2" in public\ *|*SicarioGaming/Starwire|*SicarioGaming/Starwire.git) echo "pre-push: no $f here, so nothing goes to the public repo"; exit 1;; esac' > .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```

As a hook it only looks at pushes to the public repo (the remote named `public`, or its URL). There
it lets through only `main`, `history-preview` and new annotated `v*` tags, so a stray
`git push public --tags`, `--all` or `--mirror` stops before it can publish Moonlight X's tags or a
backup branch, and it runs the check on everything else. On a branch that doesn't have the script
yet, pushes to the public repo stop and pushes anywhere else go through.

Two things it can't see: the submodule (common-c commits are published from their own repo) and
what's already public. If a secret ever does get out, removing it from history doesn't help: change
the secret.

## Releasing after the switch

Work happens on branches in the private repo. `main` is released code and docs, the same in both
repos. The repo's own git identity is `SicarioGaming`; set `TZ=UTC` for every commit and tag so the
dates are in UTC.

1. On the release branch: version and notes as in [UPDATING.md](UPDATING.md) (section 3, steps 1
   and 2), the release checklist done and the on-device tests passing
   (`./gradlew connectedNonRootDebugAndroidTest`).
2. Merge it into `main` as one merge commit:

   ```
   git switch main
   TZ=UTC git merge --no-ff <branch> -m "Starwire X.Y.Z"
   ```

3. Tag it, annotated:

   ```
   TZ=UTC git tag -a vX.Y.Z -m "Starwire X.Y.Z"
   ```

4. If the release moved the moonlight-common-c submodule, push that commit to the public common-c
   repo first, and check it's there:
   `git -C app/src/main/jni/moonlight-core/moonlight-common-c branch -r --contains <commit>` should
   list its public `main`.
5. Check it. Both must print `clean`:

   ```
   bash .github/tools/check-publish.sh public/main main
   bash .github/tools/check-publish.sh public/main vX.Y.Z
   ```

6. Push, `main` first and the tag last, and to the public repo one ref at a time:

   ```
   git push origin main vX.Y.Z
   git push public main
   git push public vX.Y.Z
   ```

   The tag push to the public repo runs the **Release** workflow, which builds and signs the APK and
   publishes the release with the notes and checksums. (If Actions are on in the private repo, the
   tag push there runs it too and stops at once, because the signing secrets are only on the public
   repo. That's expected.)
7. `gh release view vX.Y.Z --repo SicarioGaming/Starwire` lists the APK (and the microphone zip)
   with checksums.

Never toward the public repo: `--force`, `--tags`, `--all` or `--mirror`, moving a tag that's
already out, or rewriting a commit that's already out. Wording fixes are new commits.

## Afterwards

- Move `private/publish-history.sh` to `private/archive/`, since nothing uses it any more.
- Stop fetching upstream tags so Moonlight X's own `v0.x` tags can't mix with Starwire's:
  `git config remote.moonlightx.tagOpt --no-tags` (and the same for `artemide` and `turnip`).
- In UPDATING.md, point section 3 at this page. Its step 3 (`git push origin v<version>`) only
  reaches the private repo, and `set-release-secrets.ps1` gives the signing secrets to the public
  repo only, so that push can't make a release.
- Bring Moonlight X in with real merges (`git merge moonlightx/mlsoft-x`), which keeps its authors,
  and port single commits from other forks with `git cherry-pick -x`, which records where each one
  came from.
