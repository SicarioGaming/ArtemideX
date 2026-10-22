# Keeping Starwire current

Starwire follows several Moonlight versions. When they add something good, this is how it gets
into Starwire and out to people.

## 1. See what's new

Every Monday a GitHub workflow runs `.github/tools/upstream/check.sh` and opens (or updates) an issue
called **Upstream changes** listing new commits in each source. To check by hand:

```bash
.github/tools/upstream/check.sh
```

The sources and the last commit reviewed for each are in `.github/tools/upstream/sources.txt`.

## 2. Bring a feature in

- **Ported code** (Moonlight X, Artemide, Moonlight X Turnip, moonlight-common-c): add the source
  as a git remote and port the commits by hand. Starwire's base has moved on, so plain merges
  rarely apply cleanly.
- **Ideas** (Moonlight V+, Moonlit Artemis, upstream Moonlight): write the feature for Starwire.
- Add an on-device test for it (`app/src/androidTest`) and run them all:
  `./gradlew connectedNonRootDebugAndroidTest`.
- Credit it in the README's **Where every feature comes from** table (project, author, and
  whether it was ported or an idea).
- Move that source's last-reviewed commit forward in `.github/tools/upstream/sources.txt`, even for
  commits you decided to skip.

## 3. Release it

1. Raise `versionName` and `versionCode` in `app/build.gradle`.
2. Write the notes in `.github/release-notes/v<version>-notes.md` (plain, first person). Leave
   `APK_SHA256` and `CERT_SHA256` where the checksums go; the release fills them in.
3. Tag and push: `git tag v<version> && git push origin v<version>`.

The **Release** workflow builds the signed APK, checks its signature, and publishes the GitHub
release with the notes and checksums. It needs these repository secrets (Settings → Secrets and
variables → Actions), set once:

| Secret | Value |
| --- | --- |
| `STARWIRE_KEYSTORE_BASE64` | the release keystore file, base64-encoded |
| `STARWIRE_KEYSTORE_PASSWORD` | the keystore password |
| `STARWIRE_KEY_ALIAS` | `starwire` |
| `STARWIRE_KEY_PASSWORD` | the key password |

With the GitHub CLI signed in, `.\tools\set-release-secrets.ps1` sets all four from
`keystore.properties` without showing the passwords.

Tags with a hyphen (like `v0.2.0-beta1`) are published as pre-releases.
