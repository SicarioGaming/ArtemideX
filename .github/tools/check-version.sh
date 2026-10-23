#!/usr/bin/env bash
# Checks a release tag against Semantic Versioning 2.0.0 (https://semver.org/spec/v2.0.0.html) and
# the rules in docs/dev/RELEASING.md ("Version numbers"), before the tag becomes a release.
#
#   bash .github/tools/check-version.sh --tag vX.Y.Z     (from the repo root)
#   bash .github/tools/check-version.sh --self-test
#
#   --tag        checks the commit checked out as release vX.Y.Z:
#                - the tag is "v" and a SemVer version. Build metadata (a "+..." part) isn't used.
#                - versionName in app/build.gradle is X.Y.Z, and versionCode is a whole number
#                  higher than it was at the previous release.
#                - CHANGELOG.md has a "## [X.Y.Z] - YYYY-MM-DD" heading and a "[X.Y.Z]: https://..."
#                  link, and .github/release-notes/vX.Y.Z-notes.md exists. A pre-release, like
#                  v0.3.0-beta.1, skips this.
#                - the bump fits the commits since the last release: a feat commit needs at least a
#                  MINOR bump, and so does a breaking one ("!" before the colon, or a
#                  "BREAKING CHANGE:" footer) while MAJOR is 0. From 1.0.0 on a breaking one needs
#                  MAJOR. A pre-release counts as the bump of the version it leads to.
#   --self-test  runs planted tags, versions and releases (in a throwaway repo in a temporary
#                folder) and says if any is classified wrong.
#
# The previous release is the highest tag below this one by SemVer precedence, among the tags
# named "v" and a SemVer version. Other tags, like moonlightx/v0.2.13 or v0.3-beta1, are ignored.
# The bump is measured from the last release that isn't a pre-release, since everything after it
# ships in this one. Merge commits are skipped, but not the commits they bring in.
# It only reads the repo: no network, no changes, and the same input always gives the same output.
# Exit 0 = fine, 1 = something to fix, 2 = usage error.
#
# Needs bash and git (both in Git for Windows). It avoids bash 4 features, so macOS's bash works.

set -u
export LC_ALL=C

# v0.2.1's number was announced before these rules, so it skips the bump check (RELEASING.md).
EXEMPT_TAG=v0.2.1

GRADLE=app/build.gradle
CHANGELOG=CHANGELOG.md
NOTES_DIR=.github/release-notes

# SemVer's own regex (semver.org, "Is there a suggested regular expression"), in the POSIX form
# bash's =~ takes: \d is [0-9] and (?:...) is (...). Its build metadata part is left out.
RE_NUM='(0|[1-9][0-9]*)'
RE_ID='(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)'
RE_VERSION="^$RE_NUM\\.$RE_NUM\\.$RE_NUM(-$RE_ID(\\.$RE_ID)*)?\$"
RE_DATE='^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'
RE_CODE='^[1-9][0-9]*$'
RE_GRADLE_NAME='^[[:space:]]*versionName([[:space:]]+|[[:space:]]*=[[:space:]]*)"([^"]*)"'
RE_GRADLE_CODE='^[[:space:]]*versionCode([[:space:]]+|[[:space:]]*=[[:space:]]*)([^[:space:]]*)'
RE_FEAT='^[Ff][Ee][Aa][Tt](\([^()]*\))?!?:'
RE_BANG='^[A-Za-z]+(\([^()]*\))?!:'

usage() {
  echo "usage: check-version.sh --tag vX.Y.Z" >&2
  echo "       check-version.sh --self-test" >&2
  exit 2
}

# GitHub Actions annotation, so the problem shows on the run's page too.
gh_error() {
  [ "${GITHUB_ACTIONS:-}" = true ] || return 0
  local m=$2
  m=${m//%/%25}; m=${m//$'\r'/%0D}; m=${m//$'\n'/%0A}
  printf '::error title=%s::%s\n' "$1" "$m"
}

problem() { echo "$1"; gh_error "Version check" "$1"; NPROB=$((NPROB + 1)); }
note() { echo "note: $1"; }

is_version() { [[ $1 =~ $RE_VERSION ]]; }

# --- comparing versions ---------------------------------------------------------------------------
# num_cmp A B sets CMP to -1, 0 or 1. A and B are digit strings without leading zeros, compared by
# length first, so numbers of any size work (bash's own arithmetic stops at 2^63).
num_cmp() {
  if [ ${#1} -ne ${#2} ]; then
    if [ ${#1} -lt ${#2} ]; then CMP=-1; else CMP=1; fi
  elif [ "$1" = "$2" ]; then CMP=0
  elif [[ $1 < $2 ]]; then CMP=-1
  else CMP=1; fi
}

# vcmp A B sets CMP to -1, 0 or 1 by SemVer precedence (spec item 11). A and B are versions
# without the v.
vcmp() {
  local a=$1 b=$2 ap="" bp="" i x y xnum ynum
  local -a an bn ai bi
  case $a in *-*) ap=${a#*-} ;; esac
  case $b in *-*) bp=${b#*-} ;; esac
  IFS=. read -r -a an <<< "${a%%-*}"
  IFS=. read -r -a bn <<< "${b%%-*}"
  for i in 0 1 2; do
    num_cmp "${an[$i]}" "${bn[$i]}"
    [ $CMP -eq 0 ] || return 0
  done
  # The same MAJOR.MINOR.PATCH: a pre-release comes before the release itself
  if [ -z "$ap" ] || [ -z "$bp" ]; then
    if [ -z "$ap" ] && [ -z "$bp" ]; then CMP=0
    elif [ -z "$ap" ]; then CMP=1
    else CMP=-1; fi
    return 0
  fi
  # Two pre-releases: compare the dot-separated identifiers from the left. Numbers compare as
  # numbers and come before words, words compare in ASCII order, and if one runs out first, it's
  # the lower one.
  IFS=. read -r -a ai <<< "$ap"
  IFS=. read -r -a bi <<< "$bp"
  i=0
  while [ $i -lt ${#ai[@]} ] && [ $i -lt ${#bi[@]} ]; do
    x=${ai[$i]} y=${bi[$i]} xnum=0 ynum=0
    case $x in *[!0-9]*) ;; *) xnum=1 ;; esac
    case $y in *[!0-9]*) ;; *) ynum=1 ;; esac
    if [ $xnum = 1 ] && [ $ynum = 1 ]; then num_cmp "$x" "$y"
    elif [ $xnum = 1 ]; then CMP=-1
    elif [ $ynum = 1 ]; then CMP=1
    elif [ "$x" = "$y" ]; then CMP=0
    elif [[ $x < $y ]]; then CMP=-1
    else CMP=1; fi
    [ $CMP -eq 0 ] || return 0
    i=$((i + 1))
  done
  num_cmp ${#ai[@]} ${#bi[@]}
}

# pick_previous VERSION TAG... sets PREV, the highest tag below VERSION, and BASE, the highest one
# below it that isn't a pre-release. Either is "" when there's none. Only tags named "v" and a
# SemVer version count. Build metadata isn't used here, so tags with it don't count either.
pick_previous() {
  local v=$1 t tv
  shift
  PREV="" BASE=""
  for t in "$@"; do
    case $t in v*) ;; *) continue ;; esac
    tv=${t#v}
    is_version "$tv" || continue
    vcmp "$tv" "$v"
    [ $CMP -lt 0 ] || continue
    if [ -z "$PREV" ]; then PREV=$t; else vcmp "$tv" "${PREV#v}"; [ $CMP -le 0 ] || PREV=$t; fi
    case $tv in *-*) continue ;; esac
    if [ -z "$BASE" ]; then BASE=$t; else vcmp "$tv" "${BASE#v}"; [ $CMP -le 0 ] || BASE=$t; fi
  done
}

# bump_level FROM TO sets LEVEL to MAJOR, MINOR or PATCH: the first of the three numbers that TO
# raises over FROM. FROM is a release and TO is higher. TO's pre-release label doesn't count.
bump_level() {
  local -a f t
  IFS=. read -r -a f <<< "${1%%-*}"
  IFS=. read -r -a t <<< "${2%%-*}"
  num_cmp "${t[0]}" "${f[0]}"
  if [ $CMP -gt 0 ]; then LEVEL=MAJOR; return; fi
  num_cmp "${t[1]}" "${f[1]}"
  if [ $CMP -gt 0 ]; then LEVEL=MINOR; else LEVEL=PATCH; fi
}

rank() { case $1 in MAJOR) echo 3 ;; MINOR) echo 2 ;; *) echo 1 ;; esac; }

# --- reading the files and the commits ------------------------------------------------------------
# check_tag_name TAG sets TAG_PROBLEM to what's wrong with TAG as a release tag, or "".
check_tag_name() {
  local t=$1 v=${1#v}
  TAG_PROBLEM=""
  case $t in
    v*) ;;
    *) TAG_PROBLEM="the tag \"$t\" doesn't start with \"v\": release tags look like v0.3.0"; return ;;
  esac
  case $v in
    *+*)
      if is_version "${v%%+*}"; then
        TAG_PROBLEM="the tag \"$t\" has build metadata (the \"+...\" part), which Starwire doesn't use: tag v${v%%+*} instead"
      else
        TAG_PROBLEM="the tag \"$t\" has build metadata (the \"+...\" part), which Starwire doesn't use, and the rest isn't a SemVer version either"
      fi
      return ;;
  esac
  is_version "$v" || TAG_PROBLEM="the tag \"$t\" isn't \"v\" and a SemVer version: three numbers without leading zeros, like v0.3.0, and maybe a pre-release label, like v0.3.0-beta.1"
}

# read_gradle TEXT sets GRADLE_NAME and GRADLE_CODE from the first versionName and versionCode
# lines in TEXT, and HAS_NAME and HAS_CODE to 1 if there were such lines.
read_gradle() {
  local line
  GRADLE_NAME="" GRADLE_CODE="" HAS_NAME=0 HAS_CODE=0
  while IFS= read -r line; do
    line=${line%$'\r'}
    if [ $HAS_NAME = 0 ] && [[ $line =~ $RE_GRADLE_NAME ]]; then
      GRADLE_NAME=${BASH_REMATCH[2]} HAS_NAME=1
    elif [ $HAS_CODE = 0 ] && [[ $line =~ $RE_GRADLE_CODE ]]; then
      GRADLE_CODE=${BASH_REMATCH[2]} HAS_CODE=1
    fi
  done <<< "$1"
}

# commit_kind MESSAGE sets KIND to breaking, feat or other, by Conventional Commits 1.0.0: breaking
# for a "!" before the colon or a BREAKING CHANGE footer (which must be in capitals), feat for the
# feat type, in any case, with or without a scope.
commit_kind() {
  local msg=${1//$'\r'/} subject line
  subject=${msg%%$'\n'*}
  KIND=other
  if [[ $subject =~ $RE_BANG ]]; then KIND=breaking; return; fi
  if [ "$subject" != "$msg" ]; then
    while IFS= read -r line; do
      case $line in "BREAKING CHANGE:"*|"BREAKING-CHANGE:"*) KIND=breaking; return ;; esac
    done <<< "${msg#*$'\n'}"
  fi
  if [[ $subject =~ $RE_FEAT ]]; then KIND=feat; fi
}

# --- one tag --------------------------------------------------------------------------------------
# check_tag TAG checks the commit checked out as release TAG, prints one line per problem and a
# summary, and returns 1 if there was a problem.
check_tag() {
  local tag=$1 v pre="" t line d heading=0 link=0 bad_heading="" notes what need sug
  local n_feat=0 n_break=0 first_feat="" first_break="" rec sha msg code="" prev_text
  local -a tags nv nb
  NPROB=0 LEVEL=""

  check_tag_name "$tag"
  if [ -n "$TAG_PROBLEM" ]; then
    problem "$TAG_PROBLEM"
    echo "$tag: not a release tag, 1 problem to fix"
    return 1
  fi
  v=${tag#v}
  case $v in *-*) pre=${v#*-} ;; esac

  tags=()
  while IFS= read -r t; do tags+=("$t"); done < <(git tag -l)
  pick_previous "$v" ${tags[@]+"${tags[@]}"}
  if [ -z "$PREV" ]; then
    echo "checking $tag at $(git rev-parse --short=10 HEAD), with no earlier release tag"
  elif [ "$PREV" = "$BASE" ]; then
    echo "checking $tag at $(git rev-parse --short=10 HEAD), after $PREV"
  elif [ -n "$BASE" ]; then
    echo "checking $tag at $(git rev-parse --short=10 HEAD), after $PREV (the last release that isn't a pre-release is $BASE)"
  else
    echo "checking $tag at $(git rev-parse --short=10 HEAD), after $PREV (no earlier release that isn't a pre-release)"
  fi

  # versionName and versionCode
  read_gradle "$(cat "$GRADLE")"
  if [ $HAS_NAME = 0 ]; then
    problem "$GRADLE has no versionName line"
  elif [ "$GRADLE_NAME" != "$v" ]; then
    problem "versionName in $GRADLE is \"$GRADLE_NAME\", not \"$v\""
  fi
  if [ $HAS_CODE = 0 ]; then
    problem "$GRADLE has no versionCode line"
  elif [[ ! $GRADLE_CODE =~ $RE_CODE ]]; then
    problem "versionCode in $GRADLE is \"$GRADLE_CODE\", not a whole number above 0"
  else
    code=$GRADLE_CODE
  fi
  if [ -z "$PREV" ]; then
    note "no earlier release tag, so versionCode and the bump aren't compared with one"
  elif ! prev_text=$(git cat-file blob "refs/tags/$PREV:$GRADLE" 2>/dev/null); then
    problem "can't read $GRADLE at $PREV, to compare versionCode with"
  else
    read_gradle "$prev_text"
    if [[ ! $GRADLE_CODE =~ $RE_CODE ]]; then
      problem "can't read versionCode in $GRADLE at $PREV, to compare with"
    elif [ -n "$code" ]; then
      num_cmp "$code" "$GRADLE_CODE"
      [ $CMP -gt 0 ] || problem "versionCode in $GRADLE is $code, which isn't higher than $GRADLE_CODE at $PREV"
    fi
  fi

  # The changelog and the release notes, for a release
  if [ -n "$pre" ]; then
    note "$tag is a pre-release, so CHANGELOG.md and the release notes aren't checked"
  else
    if [ ! -f "$CHANGELOG" ]; then
      problem "there's no $CHANGELOG"
    else
      while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        case $line in
          "## [$v] - "*)
            d=${line#"## [$v] - "}
            if [[ $d =~ $RE_DATE ]]; then heading=1; else bad_heading=$line; fi ;;
          "[$v]: https://"*) link=1 ;;
        esac
      done < "$CHANGELOG"
      if [ $heading = 0 ] && [ -n "$bad_heading" ]; then
        problem "$CHANGELOG's heading \"$bad_heading\" isn't \"## [$v] - YYYY-MM-DD\""
      elif [ $heading = 0 ]; then
        problem "$CHANGELOG has no \"## [$v] - YYYY-MM-DD\" heading"
      fi
      [ $link = 1 ] || problem "$CHANGELOG has no \"[$v]: https://...\" link at the bottom"
    fi
    notes=$NOTES_DIR/$tag-notes.md
    if [ ! -f "$notes" ]; then problem "$notes doesn't exist"
    elif [ ! -s "$notes" ]; then problem "$notes is empty"
    fi
  fi

  # The bump
  if [ -n "$BASE" ]; then bump_level "${BASE#v}" "$v"; fi
  if [ -z "$PREV" ]; then
    :
  elif [ -z "$BASE" ]; then
    note "no earlier release that isn't a pre-release, so the bump isn't checked"
  elif [ "$tag" = "$EXEMPT_TAG" ]; then
    note "$tag skips the bump check: its number was announced before the version rules (docs/dev/RELEASING.md)"
  else
    IFS=. read -r -a nv <<< "${v%%-*}"
    IFS=. read -r -a nb <<< "${BASE#v}"
    # SemVer items 7 and 8: raising a number sets the ones after it back to 0
    if [ $LEVEL = MAJOR ] && [ "${nv[1]}.${nv[2]}" != 0.0 ]; then
      problem "$tag raises MAJOR over $BASE, so MINOR and PATCH go back to 0: v${nv[0]}.0.0"
    elif [ $LEVEL = MINOR ] && [ "${nv[2]}" != 0 ]; then
      problem "$tag raises MINOR over $BASE, so PATCH goes back to 0: v${nv[0]}.${nv[1]}.0"
    fi
    while IFS= read -r -d '' rec || [ -n "$rec" ]; do
      sha=${rec%%$'\n'*} msg=${rec#*$'\n'}
      commit_kind "$msg"
      msg=${msg%%$'\n'*}
      case $KIND in
        breaking)
          n_break=$((n_break + 1))
          [ -n "$first_break" ] || first_break="${sha:0:10} \"${msg%$'\r'}\"" ;;
        feat)
          n_feat=$((n_feat + 1))
          [ -n "$first_feat" ] || first_feat="${sha:0:10} \"${msg%$'\r'}\"" ;;
      esac
      rec=""
    done < <(git log -z --reverse --no-merges --no-show-signature --format='%H%n%B' "refs/tags/$BASE..HEAD")
    what="a $LEVEL release"
    [ -z "$pre" ] || what="a pre-release of a $LEVEL release"
    if [ $n_break -gt 0 ]; then
      if [ "${nb[0]}" = 0 ]; then need=MINOR; else need=MAJOR; fi
      if [ "$(rank $LEVEL)" -lt "$(rank $need)" ]; then
        if [ $need = MAJOR ]; then
          sug="it needs a MAJOR bump: v$((nb[0] + 1)).0.0"
        else
          sug="it needs at least a MINOR bump while MAJOR is 0: v${nb[0]}.$((nb[1] + 1)).0"
        fi
        problem "$tag is $what, but $n_break commit(s) since $BASE are marked breaking (the first is $first_break), so $sug"
      fi
    fi
    if [ $n_feat -gt 0 ] && [ "$(rank $LEVEL)" -lt 2 ]; then
      problem "$tag is $what, but $n_feat commit(s) since $BASE add a feature (the first is $first_feat), so it needs at least a MINOR bump: v${nb[0]}.$((nb[1] + 1)).0"
    fi
  fi

  # The summary
  if [ -n "$LEVEL" ]; then
    what="$LEVEL release after $BASE"
    [ -z "$pre" ] || what="pre-release of a $what"
  elif [ -z "$PREV" ]; then
    what="first release"
    [ -z "$pre" ] || what="first release, a pre-release"
  else
    what="release after the pre-release $PREV"
    [ -z "$pre" ] || what="pre-release after the pre-release $PREV"
  fi
  if [ $NPROB -eq 0 ]; then echo "$tag: $what, fine"; return 0; fi
  echo "$tag: $what, $NPROB problem(s) to fix"
  return 1
}

# --- self-test ------------------------------------------------------------------------------------
self_test() {
  local total=0 wrong=0 tmp root s when=1792800000 i
  local -a spec

  result() { # expected got label
    total=$((total + 1))
    if [ "$1" = "$2" ]; then printf 'ok     %-12s %s\n' "$2" "$3"
    else printf 'WRONG  %-12s %s (expected %s)\n' "$2" "$3" "$1"; wrong=$((wrong + 1)); fi
  }
  name() { # expected tag
    check_tag_name "$2"
    if [ -z "$TAG_PROBLEM" ]; then s=good; else s=bad; fi
    result "$1" "$s" "tag name: \"${2//$'\n'/\\n}\""
  }
  below() { # A B, where A has lower precedence than B
    vcmp "$1" "$2"; result -1 "$CMP" "precedence: $1 < $2"
    vcmp "$2" "$1"; result 1 "$CMP" "precedence: $2 > $1"
  }
  kind() { # expected message
    commit_kind "$2"
    result "$1" "$KIND" "commit: ${2//$'\n'/\\n}"
  }
  previous() { # expected-previous expected-base version tags...
    local p=$1 b=$2
    shift 2
    pick_previous "$@"
    result "$p" "${PREV:-none}" "previous of $1 among: ${*:2}"
    result "$b" "${BASE:-none}" "last release of $1 among: ${*:2}"
  }

  # Tag names
  for s in v0.0.4 v1.2.3 v10.20.30 v0.2.1 v0.3.0-beta.1 v1.1.2-prerelease v1.0.0-alpha \
           v1.0.0-alpha.beta v1.0.0-alpha.1 v1.0.0-alpha0.valid v1.0.0-alpha.0valid v1.0.0-rc.1 \
           v1.0.0-0A.is.legal v1.0.0-x-y-z.-- v1.2.3----RC-SNAPSHOT.12.9.1--.12 \
           v99999999999999999999999.999999999999999999.99999999999999999; do
    name good "$s"
  done
  for s in 1.2.3 V1.2.3 v v1 v1.2 v1.2.3.4 v01.1.1 v1.01.1 v1.1.01 v00.0.0 v1.2.3-0123 \
           v1.2.3-01.2 v1.2.3-alpha.01 v1.2.3- v1.2.3-alpha..1 v1.2.3-.beta v1.2.3-beta. \
           v1.2.3-alpha_1 v1.2.3+build.1 v1.2.3-beta+exp.sha.5114f85 v1.0.0+0.build.1-rc.10000aaa-kk-0.1 \
           v1.2+build v0.3-beta1 v12.1-turnip starwire-v0.2.0 moonlightx/v0.2.13 v-1.2.3 "v1.2.3 " \
           " v1.2.3" "v1.2.3"$'\n' ""; do
    name bad "$s"
  done
  check_tag_name v1.2.3+build.1
  case $TAG_PROBLEM in *"build metadata"*"tag v1.2.3 instead") s=says-why ;; *) s=unclear ;; esac
  result says-why "$s" "v1.2.3+build.1 is turned down for its build metadata"

  # Precedence: the spec's own example (item 11), then numbers against words
  spec=(1.0.0-alpha 1.0.0-alpha.1 1.0.0-alpha.beta 1.0.0-beta 1.0.0-beta.2 1.0.0-beta.11
        1.0.0-rc.1 1.0.0)
  i=1
  while [ $i -lt ${#spec[@]} ]; do below "${spec[$((i - 1))]}" "${spec[$i]}"; i=$((i + 1)); done
  below 1.0.0 2.0.0
  below 2.0.0 2.1.0
  below 2.1.0 2.1.1
  below 0.9.0 0.10.0
  below 0.2.1 0.2.13
  below 1.9.9 1.10.0
  below 1.99.99 2.0.0
  below 9999999999999999999.0.0 10000000000000000000.0.0
  below 0.3.0-beta.1 0.3.0
  below 0.2.1 0.3.0-beta.1
  below 1.0.0-alpha.9 1.0.0-alpha.10
  below 1.0.0-alpha.10 1.0.0-alpha.1a
  below 1.0.0-B 1.0.0-a
  below 1.0.0-beta 1.0.0-beta.0
  vcmp 1.0.0-rc.1 1.0.0-rc.1; result 0 "$CMP" "precedence: 1.0.0-rc.1 = 1.0.0-rc.1"
  vcmp 0.2.1 0.2.1; result 0 "$CMP" "precedence: 0.2.1 = 0.2.1"

  # The previous release, among tags like the ones the private repo had
  previous v0.2.0 v0.2.0 0.2.1 v0.1.0 v0.1.2 v0.2.0 v0.2.13 v0.2.16 v0.3-beta1 v0.3.0 \
    starwire-v0.2.0 v12.1-turnip moonlightx/v0.2.1 Release
  previous v0.1.2 v0.1.2 0.2.0 v0.2.0 v0.1.2 v0.1.0 v0.2.1
  previous v0.10.0 v0.10.0 0.10.1 v0.9.0 v0.10.0 v0.2.0
  previous v1.0.0-rc.1 none 1.0.0 v1.0.0-beta.11 v1.0.0-alpha v1.0.0-rc.1 v1.0.0-beta.2 \
    v1.0.0-alpha.beta v1.0.0-beta v1.0.0-alpha.1
  previous v1.0.0-beta.2 none 1.0.0-beta.11 v1.0.0-beta.11 v1.0.0-alpha v1.0.0-rc.1 v1.0.0-beta.2 \
    v1.0.0-alpha.beta v1.0.0-beta v1.0.0-alpha.1
  previous v0.3.0-beta.2 v0.2.1 0.3.0 v0.3.0-beta.1 v0.3.0 v0.2.1 v0.3.0-beta.2
  previous v0.9.0 v0.9.0 1.0.1 v0.9.0 v1.0.0+build.5 v1.0.1+build.6
  previous none none 0.1.0 v0.1.0 v0.3-beta1 starwire-v0.2.0

  # Commit messages
  kind feat "feat: add custom resolutions"
  kind feat "feat(mic): add a mute button"
  kind feat "Feat(mic): add a mute button"
  kind breaking "feat!: drop android 5"
  kind breaking "fix(mic)!: move the mic to another port"
  kind breaking "chore!: drop the old settings"
  kind breaking $'fix: change the settings format\n\nBREAKING CHANGE: old settings are reset'
  kind breaking $'feat: change the settings format\n\nRefs: #12\nBREAKING-CHANGE: old settings are reset'
  kind breaking $'refactor: move the settings\r\n\r\nBREAKING CHANGE: old settings are reset\r\n'
  kind other "fix: stop the crash"
  kind other "feature: add a thing"
  kind other "fix: add the feat: prefix to the docs"
  kind other "docs(feat): explain the features"
  kind other "Add custom resolutions"
  kind other $'fix: change the settings format\n\nbreaking change: written in lower case'
  kind other $'fix: change the settings format\n\nRefs: BREAKING CHANGE: not at the start'
  kind other "fix: stop the crash!: really"

  # Releases, in a throwaway repo
  tmp=$(mktemp -d) || { echo "self-test: can't make a temporary folder" >&2; return 2; }
  root=$tmp/repo
  g() {
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$root" -c user.name=Test \
      -c user.email=test@example.invalid -c commit.gpgsign=false -c tag.gpgsign=false \
      -c core.hooksPath=/dev/null -c core.autocrlf=false "$@"
  }
  at() { # git-arguments..., each a minute after the last
    when=$((when + 60))
    GIT_AUTHOR_DATE="@$when +0000" GIT_COMMITTER_DATE="@$when +0000" g "$@"
  }
  commit() { at commit -q --allow-empty -m "$@"; }
  release() { at add -A && at commit -q --allow-empty -m "chore: release $1" && at tag -a -m "Starwire $1" "v$1"; }
  files() { # version versionCode, then what to leave out or get wrong: no-heading, bad-date,
            # no-link, no-notes, empty-notes, old-gradle
    local v=$1 code=$2 opts=" ${*:3} " date=2026-10-24 eq="= "
    mkdir -p "$root/app" "$root/$NOTES_DIR"
    case $opts in *" old-gradle "*) eq="" ;; esac
    printf '%s\n' "android {" "    defaultConfig {" "        // versionCode = 1 was the first" \
      "        versionName \"$v\"" "        versionCode $eq$code" "    }" "    buildTypes {" \
      "        debug {" "            versionNameSuffix \"-debug\"" "        }" "    }" "}" > "$root/$GRADLE"
    case $opts in *" bad-date "*) date=2026-13-01 ;; esac
    {
      printf '%s\n' "# Changelog" "" "## [Unreleased]" ""
      case $opts in
        *" no-heading "*) printf '%s\n' "## [$v]" "" ;;
        *) printf '%s\n' "## [$v] - $date" "" ;;
      esac
      printf '%s\n' "### Fixed" "" "- Something." "" "[Unreleased]: https://example.invalid/compare/v$v...HEAD"
      case $opts in *" no-link "*) ;; *) echo "[$v]: https://example.invalid/releases/tag/v$v" ;; esac
    } > "$root/$CHANGELOG"
    rm -f "$root/$NOTES_DIR/v$v-notes.md"
    case $opts in
      *" no-notes "*) ;;
      *" empty-notes "*) : > "$root/$NOTES_DIR/v$v-notes.md" ;;
      *) echo "What's new." > "$root/$NOTES_DIR/v$v-notes.md" ;;
    esac
  }
  check() { # expected-exit tag label [text the output must have]
    local out status
    out=$(cd "$root" && unset GIT_DIR GIT_WORK_TREE && GIT_CONFIG_NOSYSTEM=1 \
      GIT_CONFIG_GLOBAL=/dev/null GITHUB_ACTIONS= bash "$SELF" --tag "$2" 2>&1)
    status=$?
    result "$1" "$status" "--tag $2: $3"
    if [ -n "${4:-}" ]; then
      case $out in *"$4"*) s=says-so ;; *) s=doesnt; printf '%s\n' "$out" | sed 's/^/         | /' ;; esac
      result says-so "$s" "  and says: $4"
    fi
  }
  if ! GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -c init.defaultBranch=main init -q "$root"; then
    rm -rf "$tmp"; echo "self-test: git init failed" >&2; return 2
  fi

  # The first release, with tags around it that aren't Starwire releases
  files 0.1.0 1 old-gradle
  at add -A && commit "chore: start the project"
  for s in v0.3-beta1 starwire-v0.2.0 v12.1-turnip v9.9.9+build.1 moonlightx/v0.0.1 Release; do g tag "$s"; done
  check 0 v0.1.0 "the first release" "v0.1.0: first release, fine"
  release 0.1.0

  # A PATCH release with only fixes, and what can be wrong in one
  commit "fix: stop the crash"
  files 0.1.0 2;              check 1 v0.1.1 "versionName not bumped" 'versionName in app/build.gradle is "0.1.0", not "0.1.1"'
  files 0.1.1 1;              check 1 v0.1.1 "versionCode the same as v0.1.0's" "isn't higher than 1 at v0.1.0"
  files 0.1.1 2a;             check 1 v0.1.1 "versionCode not a number" "not a whole number"
  files 0.1.1 2 no-heading;   check 1 v0.1.1 "no changelog heading" 'no "## [0.1.1] - YYYY-MM-DD" heading'
  files 0.1.1 2 bad-date;     check 1 v0.1.1 "a changelog heading with month 13" "isn't \"## [0.1.1] - YYYY-MM-DD\""
  files 0.1.1 2 no-link;      check 1 v0.1.1 "no changelog link" 'no "[0.1.1]: https://..." link'
  files 0.1.1 2 no-notes;     check 1 v0.1.1 "no release notes" "v0.1.1-notes.md doesn't exist"
  files 0.1.1 2 empty-notes;  check 1 v0.1.1 "empty release notes" "v0.1.1-notes.md is empty"
  files 0.1.1 2;              check 0 v0.1.1 "fixes only" "v0.1.1: PATCH release after v0.1.0, fine"
  check 1 v0.1.1+build.2 "build metadata" "build metadata"
  check 1 v0.1 "no PATCH number" "isn't \"v\" and a SemVer version"
  check 1 v0.01.1 "a leading zero" "isn't \"v\" and a SemVer version"
  release 0.1.1

  # A feat commit needs at least MINOR
  commit "feat(mic): add a mute button"
  files 0.1.2 3;              check 1 v0.1.2 "a feat commit in a PATCH release" "so it needs at least a MINOR bump: v0.2.0"
  files 0.1.2-beta.1 3 no-heading no-link no-notes
  check 1 v0.1.2-beta.1 "a feat commit in a pre-release of a PATCH release" "needs at least a MINOR bump"
  files 0.2.5 3;              check 1 v0.2.5 "MINOR raised but PATCH not back to 0" "so PATCH goes back to 0: v0.2.0"
  files 0.2.0 3;              check 0 v0.2.0 "a feat commit in a MINOR release" "v0.2.0: MINOR release after v0.1.1, fine"
  release 0.2.0

  # Merge commits don't count, but what they bring in does. v0.2.1 is exempt from the bump check.
  at checkout -q -b side
  commit "fix: a fix on a side branch"
  at checkout -q main
  at merge -q --no-ff -m "feat!: merge the side branch" side
  files 0.2.2 4;              check 0 v0.2.2 "a merge commit with a breaking subject" "v0.2.2: PATCH release after v0.2.0, fine"
  at checkout -q side
  commit "feat: add custom resolutions"
  at checkout -q main
  at merge -q --no-ff -m "Merge branch 'side'" side
  files 0.2.2 4;              check 1 v0.2.2 "a feat commit brought in by a merge" "1 commit(s) since v0.2.0 add a feature"
  files 0.2.1 4;              check 0 v0.2.1 "the changes v0.2.2 failed on, but v0.2.1 is exempt" "v0.2.1 skips the bump check"
  files 0.3.0-beta.1 4 no-heading no-link no-notes
  check 0 v0.3.0-beta.1 "a pre-release without a changelog entry" "v0.3.0-beta.1: pre-release of a MINOR release after v0.2.0, fine"
  at add -A && at commit -q -m "chore: release 0.3.0-beta.1" && at tag -a -m "Starwire 0.3.0-beta.1" v0.3.0-beta.1

  # After a pre-release: versionCode goes up from it, the bump is measured from v0.2.0
  commit "fix: polish the new screen"
  files 0.3.0-beta.2 5 no-notes
  check 0 v0.3.0-beta.2 "the next pre-release" "after v0.3.0-beta.1 (the last release that isn't a pre-release is v0.2.0)"
  files 0.3.0 4;              check 1 v0.3.0 "versionCode the same as the pre-release's" "isn't higher than 4 at v0.3.0-beta.1"
  files 0.3.0 5;              check 0 v0.3.0 "the release after its pre-release" "v0.3.0: MINOR release after v0.2.0, fine"
  release 0.3.0

  # A breaking commit while MAJOR is 0 needs MINOR
  commit "fix(mic)!: move the mic to another port"
  files 0.3.1 6;              check 1 v0.3.1 "a breaking commit in a 0.x PATCH release" "while MAJOR is 0: v0.4.0"
  files 0.4.0 6;              check 0 v0.4.0 "a breaking commit in a 0.x MINOR release" "v0.4.0: MINOR release after v0.3.0, fine"
  release 0.4.0
  commit "fix: change the settings format" -m "BREAKING CHANGE: old settings are reset"
  files 0.4.1 7;              check 1 v0.4.1 "a BREAKING CHANGE footer in a 0.x PATCH release" "are marked breaking"
  files 1.0.1 7;              check 1 v1.0.1 "MAJOR raised but PATCH not back to 0" "so MINOR and PATCH go back to 0: v1.0.0"
  files 1.0.0 7;              check 0 v1.0.0 "1.0.0" "v1.0.0: MAJOR release after v0.4.0, fine"
  release 1.0.0

  # From 1.0.0 on: a feat commit needs MINOR, a breaking one MAJOR
  commit "feat: add a 1.x feature"
  files 1.0.1 8;              check 1 v1.0.1 "a feat commit in a 1.x PATCH release" "needs at least a MINOR bump: v1.1.0"
  files 1.1.0 8;              check 0 v1.1.0 "a feat commit in a 1.x MINOR release"
  release 1.1.0
  commit "feat(ui)!: replace the stream menu"
  files 1.2.0 9;              check 1 v1.2.0 "a breaking commit in a 1.x MINOR release" "so it needs a MAJOR bump: v2.0.0"
  files 2.0.0-rc.1 9 no-heading no-link no-notes
  check 0 v2.0.0-rc.1 "a breaking commit in a pre-release of MAJOR"
  files 2.0.0 9;              check 0 v2.0.0 "a breaking commit in a 1.x MAJOR release" "v2.0.0: MAJOR release after v1.1.0, fine"

  # Usage errors
  (cd "$root" && bash "$SELF" >/dev/null 2>&1); result 2 "$?" "no arguments exits 2"
  (cd "$root" && bash "$SELF" --tag >/dev/null 2>&1); result 2 "$?" "--tag without a tag exits 2"
  (cd "$root" && bash "$SELF" --tag v1.0.0 --what >/dev/null 2>&1); result 2 "$?" "an unknown option exits 2"
  mkdir "$tmp/empty"
  (cd "$tmp/empty" && GIT_CEILING_DIRECTORIES=$tmp bash "$SELF" --tag v1.0.0 >/dev/null 2>&1)
  result 2 "$?" "--tag outside a repo exits 2"

  rm -rf "$tmp"
  if [ $wrong -gt 0 ]; then echo "self-test: $wrong of $total wrong"; return 1; fi
  echo "self-test: all $total classified correctly"
  return 0
}

# --- main -----------------------------------------------------------------------------------------
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
tag="" have_tag=0 self_test=0
while [ $# -gt 0 ]; do
  case $1 in
    --tag) [ $# -ge 2 ] || usage; tag=$2; have_tag=1; shift 2 ;;
    --tag=*) tag=${1#*=}; have_tag=1; shift ;;
    --self-test) self_test=1; shift ;;
    -h|--help) sed -n '2,/^$/s/^# \{0,1\}//p' "$SELF"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

if [ $self_test = 1 ]; then self_test; exit $?; fi
[ $have_tag = 1 ] || usage
git rev-parse -q --verify HEAD >/dev/null 2>&1 || { echo "not in a git repository with a commit" >&2; exit 2; }
[ -f "$GRADLE" ] || { echo "no $GRADLE here: run this from the repo root" >&2; exit 2; }
check_tag "$tag"
