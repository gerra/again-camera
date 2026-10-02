# Again on Android

The Android app is the same Compose UI as iOS, in a single activity (`composeApp/src/androidMain`).
The camera is CameraX, the old photo comes through the system photo picker, saving goes through
MediaStore, and sharing uses the system share sheet.

- [What you need](#what-you-need)
- [Install on your phone](#1-install-on-your-phone)
- [Google Play](#2-google-play): [one-time setup](#one-time-setup),
  [versions and version codes](#versions-and-version-codes),
  [upload from GitHub Actions](#upload-from-github-actions), [upload by hand](#upload-by-hand),
  [testers](#testers)
- [The store listing](#3-the-store-listing)
- [Troubleshooting](#troubleshooting)

## What you need

- A **JDK 17+** and the **Android SDK** with platform 36: [Android Studio](https://developer.android.com/studio)
  installs both. Without Android Studio, the SDK's `cmdline-tools` and a `local.properties` with
  `sdk.dir=/path/to/sdk` at the repository root are enough.
- A **Google Play developer account** ([play.google.com/console](https://play.google.com/console),
  a one-time fee) for the store. Installing on your own phone needs no account at all.

## 1. Install on your phone

1. On the phone, turn on Settings › About phone › tap **Build number** seven times, then
   Settings › System › Developer options › **USB debugging**.
2. Connect it with a cable and tap **Allow** on the phone when it asks about the computer.
3. Build and install:
   ```bash
   ./gradlew :composeApp:installDebug
   ```
   Or open the repository in Android Studio, choose the phone at the top of the window, and press
   **Run**.

Without a cable: every pull request and every merge into main builds a debug APK in the
[Android workflow](../.github/workflows/android.yml).
Open the run under **Actions**, download the `again-android-<n>` artifact, copy the `.apk` to the
phone and open it; Android asks once to allow installs from that app. Each run signs its APK with
a debug key of its own, so when the phone refuses to install one over an earlier build, uninstall
that build first.

## 2. Google Play

### One-time setup

1. **The Play Console app.** Play Console › **Create app**: name **Again Camera: Then & Now**,
   default language English, an app, free. The package name is `sh.gerra.again`, set by the
   first bundle uploaded, and Play never lets it change afterwards. New apps are enrolled in
   **Play App Signing**: Play keeps the key that signs what phones install, and the repository
   only ever holds the **upload key** below, which Play checks uploads against.
2. **The upload key.** On any machine with a JDK:
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   base64 -i upload.jks | pbcopy        # Linux: base64 -w0 upload.jks
   ```
   Keep `upload.jks` and its password somewhere safe outside the repository (`.gitignore`
   already leaves out `*.jks`). With a modern `keytool` the key gets the keystore's password.
   Play registers the upload key from the first bundle it receives; if the key is ever lost,
   Play Console › **App integrity** has a form to reset it.
3. **A service account** that may publish to the app:
   - [Google Cloud console](https://console.cloud.google.com), any project › **IAM & Admin ›
     Service accounts › Create**. Name it (e.g. `play-upload`), no roles. Then **Keys › Add key ›
     JSON**; the file downloads once.
   - **Google Play Android Developer API** enabled for the project (APIs & Services › Library).
   - Play Console › **Users and permissions › Invite new users**, the service account's email
     (`…@….iam.gserviceaccount.com`), with the app permissions **View app information**,
     **Manage testing tracks and edit tester lists** and **Release to testing tracks**, on this
     app only. With just those it can never promote a build further.
4. **The first release.** Play lets the API upload only into an app that already has a release:
   create the internal testing track's first release by hand in the console, with a bundle from
   Android Studio (**Build › Generate Signed App Bundle**, the upload key above) or from
   [`bundleRelease`](#upload-by-hand). Under App integrity keep **Google-generated key** for app
   signing; the key the bundle is signed with becomes the app's upload key. Add yourself to the
   testers, save, and **start rollout**: internal testing needs no review, and the app installs
   through the Play Store within minutes.
5. **The secrets**, under **Settings › Secrets and variables › Actions**. See
   [Upload from GitHub Actions](#upload-from-github-actions) for the table.

### Versions and version codes

| Value | Where it comes from | Meaning |
|-------|---------------------|---------|
| `versionName` | `versionName` in [`composeApp/android.gradle`](../composeApp/android.gradle) | The version people see, `1.0`; raise it for each store release, with `MARKETING_VERSION` on the iOS side. |
| `versionCode` | `-Pagain.versionCode`, which the Google Play workflow sets to its run number; `1` without it | Play refuses any upload whose code is not above every one it has seen. The run number only climbs. |

A local `./gradlew :composeApp:assembleDebug` and the Android workflow's builds get code 1, which
is fine for a phone over USB. An upload by hand needs a code above the last workflow run number.

### Upload from GitHub Actions

The [Google Play workflow](../.github/workflows/play.yml) builds on a Linux runner, whose image
has the Android SDK, signs the bundle with the upload key from the repository secrets, and sends
it to a testing track with the service account through the Play Developer API. It runs from
**Actions › Google Play › Run workflow**; the optional `build_number` box overrides the run number
as the `versionCode`.

**Until the secrets exist the job does nothing.** Its first step checks them, writes which are
missing to the run summary and skips the rest. Add these five secrets:

| Secret | What it is | How to get it |
|--------|------------|---------------|
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | The upload keystore | `base64 -i upload.jks \| pbcopy` after the `keytool` line above. |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | Its store password | Chosen when the key was generated. |
| `ANDROID_UPLOAD_KEY_ALIAS` | The key's alias | `upload` in the line above. |
| `ANDROID_UPLOAD_KEY_PASSWORD` | The key's password | Chosen when the key was generated; `keytool` defaults it to the store password. |
| `PLAY_SERVICE_ACCOUNT_JSON` | The service account's key file, as is | The JSON downloaded when the key was created. Paste the whole file; line breaks are fine. |

And one optional **variable**:

| Variable | What it is |
|----------|------------|
| `PLAY_TRACK` | The track to release to; unset means `internal`. The first closed testing track is `alpha` in the API, whatever the console shows; a track created by hand goes by its own name. |

The steps are [`tools/play.py`](../tools/play.py), one command each, the way
[gains](https://github.com/gerra/gains) ships: `check-secrets`, `paths`, `settings`,
`install-signing`, `bundle`, `upload`, `cleanup`. The Play Developer API is called with the
standard library, and `openssl` on the runner signs the service account's JWT, so nothing has to
be installed. The parts that need no Google are tested by
[`tools/test_play.py`](../tools/test_play.py), which the Android workflow runs on every pull request:

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
```

Release builds are shrunk and obfuscated by R8 (`minifyEnabled` in `composeApp/android.gradle`),
so the upload also sends R8's `mapping.txt` as the bundle's deobfuscation file, and Play Console's
crash reports show the real class and method names. The bundle and its mapping file are kept as a
run artifact (`play-bundle-<number>`) for 90 days, and the key material is removed from the runner
at the end whether or not the upload worked.

### Upload by hand

A signed bundle from the command line, for the console's **Create new release** page:

```bash
./gradlew :composeApp:bundleRelease \
  -Pagain.versionCode=1234 \
  -Pagain.uploadKeystore=/path/to/upload.jks \
  -Pagain.uploadKeystorePassword=… -Pagain.uploadKeyAlias=upload -Pagain.uploadKeyPassword=…
# → composeApp/build/outputs/bundle/release/composeApp-release.aab
# → composeApp/build/outputs/mapping/release/mapping.txt
```

The keystore properties can live in `~/.gradle/gradle.properties` instead, out of the shell
history; the alias defaults to `upload` and the key password to the keystore's. Upload the
mapping file with the bundle (the release's **App bundle explorer › Downloads › ReTrace mapping
file**), or the crash reports for that version stay obfuscated.

Without `again.uploadKeystore` the release bundle is unsigned, which is what Android Studio's
**Build › Generate Signed App Bundle** expects: it signs with the key you point it at.

### Testers

- **Internal testing** (Play Console › Testing › Internal testing): up to 100 testers by email,
  no review, builds available at once. The track's page has the **opt-in link** testers accept
  before the app shows up for them in the Play Store.
- **Closed testing** takes a list or a Google Group, and every upload reaches them without a
  review; set `PLAY_TRACK` to `alpha` to send the workflow's builds there. A personal developer
  account registered since November 2023 must run a closed test with at least **12 testers opted
  in for 14 days in a row** before it can apply for production access, so start that track early.
- Promoting a tested build to production is done in the console, under the release's
  **Promote release** menu, never by the workflow.

## 3. The store listing

Already in the project:

- **No data collected**: no account, no network permission, no analytics. The Data safety form's
  answers are all "No".
- The **camera** is the one permission; the photo picker and MediaStore need none.
- A launcher icon with an adaptive background, and **English and Russian** with per-app language
  settings (`locales_config.xml`).
- `targetSdk` 36, which meets Play's target API level requirement.
- A **privacy policy** on [again.gerra.sh](site.md), linked from the app's home screen as Play
  requires.

Still to do in the Play Console, under Grow › Store presence and Policy › App content:

- **Store listing**: short and full descriptions, a 512 × 512 icon, a 1024 × 500 feature graphic,
  and at least two phone screenshots (16:9 or 9:16, 320 to 3840 px on each side). Tablet
  screenshots too, since the app runs on tablets.
- **Privacy policy URL**: `https://again.gerra.sh/privacy`, once [the site](site.md) is deployed.
- **Data safety**, **Content rating** (the questionnaire; the app has no user content), **Target
  audience** (not designed for children), **Ads** (none), **App access** (no sign-in), **News** and
  **Government** apps (neither), **Health** (no).
- **Category**: Photography.
- Then Production › Create new release, pick the tested bundle, and **Send for review**. The first
  review takes a few days.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| **"SDK location not found"** locally | Install Android Studio or write `local.properties` as above. Machines that only run the shared tests and the desktop harness can skip the SDK altogether with `-Pagain.android=false`. |
| The run summary says *Play upload skipped* | One of the five secrets is missing or empty. The summary names it. |
| `POST …/edits answered 401` | The service account key does not match the account, or the file in `PLAY_SERVICE_ACCOUNT_JSON` is not a service account key. Create a new key and paste the whole JSON. |
| `POST …/edits answered 403: The caller does not have permission` | The service account is not invited to the app in Play Console, or lacks *Release to testing tracks*. Permissions can take a few minutes to apply after inviting. |
| `… answered 404: Package not found` | No app with the package name exists in Play Console yet, or it has never had a release; the first one is created by hand ([One-time setup](#one-time-setup), step 4). |
| `… answered 400: Version code N has already been used` | The upload's version code is not above every earlier one. The run number only climbs, so this happens after an upload by hand got ahead of it: pass a higher `build_number` to the run. |
| `… answered 400: … signed with a key that is not the upload key` | The keystore in the secret is not the key Play registered from the first upload. Use the same `upload.jks`, or reset the upload key under App integrity. |
| `… answered 404: Track not found` | `PLAY_TRACK` names a track that does not exist. The first closed track is `alpha`; a custom one goes by its own name. |
| `bundleRelease` fails with R8 *Missing class* | A library references a class nothing ships. `composeApp/build/outputs/mapping/release/missing_rules.txt` holds the `-dontwarn` lines R8 asks for; copy only those into `composeApp/proguard-rules.pro`, with a comment naming the library. The Android workflow catches this on the pull request. |
| The release build crashes where the debug build doesn't | R8 removed or renamed something reached by reflection: a missing keep rule in `composeApp/proguard-rules.pro`. The mapping file turns the stack trace back into names (`retrace` in the SDK's `cmdline-tools`). |
| The upload succeeds but testers see nothing | Play processes a bundle for minutes to hours, and testers must have accepted the opt-in link. Check the track's page in the console. |
| The camera shows "can't be opened" in the emulator | Give the virtual device a camera under its settings, or use a real phone. |
