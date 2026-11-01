---
name: cut-release
description: Cut an Orknux Mobile release - freeze, changelog, version bump, screenshots, release commit and tag, then push and let CI publish the signed APK and GitHub release only when asked. Use when the user says to cut, prepare, tag or ship a mobile release (e.g. "cut 0.2.0", "let's release the app").
---

# Cutting an Orknux Mobile release

The app is released on its own, with its own version numbers. The tag is what CI
turns into the signed APK and the GitHub release, so everything that belongs in
the release lands *before* the tag, including the changelog and the screenshots.

## 0. Decide the number and the scope

- **The number is positional, not semantic.** Releases count up in sequence;
  never argue for a different number because of a release's size. Read the
  current one from `versionName` in `app/build.gradle.kts` and the newest
  `git tag --sort=-creatordate`. If the next number is not obvious, ask - it is
  the one question worth asking.
- **Declare a freeze.** In-flight work finishes; nothing new starts, however
  small. Scope is the issues carrying the release's label (`orknux_issues` with
  `labels: mobile-<version>`) plus what has landed since the last tag
  (`git log v<previous>..HEAD --oneline`). Settle it once; do not re-propose
  deferring something because it got awkward.
- **The user runs the final manual tests on a real phone and decides whether it
  ships.** A green suite is evidence for that decision, not a substitute. The
  emulator has no real microphone, so voice mode, echo cancellation and on-device
  speech to text can only be judged on a phone: say so, and ask how they went.

## 1. Preflight

- `git status` clean, apart from known untracked noise. Stage paths, never
  directories.
- Unit tests green: `./gradlew :app:testDebugUnitTest`, then
  `python scripts/test-summary.py`.
- CI green on the commit being released: `gh run list -R orknux/orknux-mobile --branch main --limit 1`.
- The release keystore secrets exist, or the release job fails after the tag:
  `gh secret list -R orknux/orknux-mobile` must list
  `ORKNUX_ANDROID_KEYSTORE`, `ORKNUX_ANDROID_KEYSTORE_PASSWORD`,
  `ORKNUX_ANDROID_KEY_ALIAS` and `ORKNUX_ANDROID_KEY_PASSWORD`. If they do not,
  stop and tell the user: the key is theirs to make and keep, and an app signed
  with a lost key can never be updated in place. Never commit a keystore.
- With a running emulator, a server with voice models and no sign-in, and
  `adb reverse tcp:8080 tcp:8080`, run the live checks:
  `./gradlew :app:connectedDebugAndroidTest`. It **uninstalls the app from every
  attached device when it ends**, the user's phone included, which signs it out:
  run it with only the emulator attached, or say so first. A skipped test proves
  nothing; report it as skipped.

## 2. Changelog (`CHANGELOG.md`)

Add `## X.Y.Z` above the previous release, or rename the **Unreleased** heading.
CI takes the release notes from this section and refuses a tag without one. Only
these three headings, spelled as the file already spells them:

```
### ✨ Added      what is new
### 🔧 Changed    what somebody upgrading will find different
### 🐛 Fixed      what was wrong
```

One line per change, written for the person deciding whether to upgrade: the
change and the one thing worth knowing about it. No issue numbers, no commit
subjects. Show the user the entry before committing it.

## 3. Bump the version

In `app/build.gradle.kts`, both of them:
- `versionName` to `X.Y.Z`. CI refuses a tag that disagrees with it.
- `versionCode` up by one, every release. Android refuses to update an app to a
  code that is not higher.

Check nothing was left behind (ignore `CHANGELOG.md`):
```
grep -rn --exclude-dir={build,.gradle,.git} "<previous version>" .
```

## 4. Screenshots - every release

```
bash scripts/screenshots.sh
```

It builds the app, photographs it on a running **emulator** against the made-up
server in `scripts/screenshot_server.py`, and writes `docs/screens/`. It refuses
anything but an emulator, because it clears the app's data. Never take these
pictures against a real server or from the user's phone: this repository is
public, and a real chat in a screenshot is published with the release.

**Look at every picture** before committing it. A screen added this release gets a
step in the script, data in the server, and a line in the README's gallery.

## 5. Commit and tag

One commit. No `Co-Authored-By` trailer.

```
git add CHANGELOG.md app/build.gradle.kts docs/screens
git commit        # subject "X.Y.Z: <headline of the release>", body: a short paragraph on what it brings
git tag vX.Y.Z
```

## 6. Ask once, then push

**Never push without being asked, every time.** Report the version, the commit and
the tag, and ask "push and release vX.Y.Z?". One yes covers this release's push
and nothing after it.

```
git push origin main vX.Y.Z
```

## 7. Watch CI to the end

```
gh run list -R orknux/orknux-mobile --branch vX.Y.Z --limit 1
gh run watch <id> -R orknux/orknux-mobile --exit-status
```

Run the watch in the background and report when it ends. The jobs, in order:
`build` (unit tests and a debug APK) -> `release` (signs the APK and creates the
GitHub release). Do not create the release by hand: a hand-made one carries no
signed APK.

**A red job:** read its log (`gh run view <id> --log-failed`), report the failure
with its output, and fix it in a new commit and a new version - never move a tag
that has been pushed.

## 8. The GitHub release

CI makes it, named `vX.Y.Z`, with that version's changelog section as its notes,
the screenshots from `docs/screens` at that tag, and `orknux-mobile-X.Y.Z.apk`.
Check it rather than assume it:
```
gh release view vX.Y.Z -R orknux/orknux-mobile --json name,body,assets --jq '{name, notes: (.body|length), assets: [.assets[].name]}'
```
Then look at the release page: the screenshots must actually draw.

## 9. After it ships

- Install the released APK on the user's phone only if they ask:
  it is signed with the release key, so Android refuses it over a debug build
  until the debug build is uninstalled, which signs the app out.
- Offer to close the issues the release delivered: check each status first, close
  the rest with a short comment naming the version.
- Tell the user, in a few lines: what was published, where, and anything that was
  red.
