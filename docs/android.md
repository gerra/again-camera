# Again on Android

The Android app is the same Compose UI as iOS, in a single activity (`composeApp/src/androidMain`).
The camera is CameraX, the old photo comes through the system photo picker, saving goes through
MediaStore, and sharing uses the system share sheet.

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

Without a cable: every push builds a debug APK in the [Android workflow](../.github/workflows/android.yml).
Open the run under **Actions**, download the `again-android-<n>` artifact, copy the `.apk` to the
phone and open it; Android asks once to allow installs from that app. Each run signs its APK with
a debug key of its own, so when the phone refuses to install one over an earlier build, uninstall
that build first.

## 2. Google Play

### The app and the upload key

1. In the Play Console › **Create app**: name **Again Camera: Then & Now**, default language
   English, an app, free. The package name is `sh.gerra.again`, set by the first bundle uploaded,
   and Play never lets it change afterwards.
2. Make an **upload key**. Google Play signs the APKs it delivers with its own key (Play App
   Signing, mandatory for new apps); the upload key only proves a bundle came from you, and Play
   can reset it if it is lost.
   ```bash
   keytool -genkeypair -keystore again-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Keep the file and its password out of the repository (`.gitignore` already leaves out `*.jks`).
   With a modern `keytool` the key gets the keystore's password, which is what the build expects
   by default.
3. Sign the release bundle with it. Put the keystore in `~/.gradle/gradle.properties` so nothing
   lands in the shell history:
   ```
   again.uploadKeystore=/Users/you/keys/again-upload.jks
   again.uploadKeystorePassword=…
   ```
   (`again.uploadKeyAlias` and `again.uploadKeyPassword` too, when they are not `upload` and the
   keystore's password.) Then:
   ```bash
   ./gradlew :composeApp:bundleRelease -Pagain.versionCode=1
   ```
   The bundle is `composeApp/build/outputs/bundle/release/composeApp-release.aab`. Without the
   properties the same command builds it unsigned, which Play does not accept.

### The first release, by hand

Play's publishing API only works for an app that already has a bundle, so the first one goes
through the console:

1. Play Console › Testing › **Internal testing** › Create new release.
2. Under App integrity, keep **Google-generated key** for app signing. Upload the `.aab`; the key
   it is signed with becomes the app's upload key.
3. Add yourself to the testers list, save, and **start rollout**. Internal testing needs no review:
   the tester link works within minutes, and the app installs through the Play Store like any
   other.

Each release needs a higher `versionCode` than the last; `versionName` (the version people see,
`1.0`) is in `composeApp/android.gradle` and changes for each store release.

### Releases from GitHub Actions

With two repository secrets (Settings › Secrets and variables › Actions) the workflow signs the
bundle with the upload key, and with a third it can upload it:

| Secret | Value |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE` | The keystore in base64: `base64 again-upload.jks` |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | Its password |
| `PLAY_SERVICE_ACCOUNT_JSON` | A service account key, see below |
| `ANDROID_UPLOAD_KEY_ALIAS` | Optional: only when the alias is not `upload` |
| `ANDROID_UPLOAD_KEY_PASSWORD` | Optional: only when the key's password is not the keystore's |

For the service account: in the [Google Cloud Console](https://console.cloud.google.com), IAM &
Admin › Service Accounts › **Create service account**, then on it Keys › Add key › **JSON**; the
downloaded file is the secret, whole. Then in the Play Console › Users and permissions › **Invite
new users** with the account's email address, and give it, for this app only, **Release apps to
testing tracks** and **View app information and download bulk reports**. With just those, the
service account can never promote a build further.

Once the secrets are set, every push signs the bundle, and **Actions › Android › Run workflow**
with **Upload the release bundle to Google Play** ticked sends it to internal testing, with the
ProGuard mapping for readable crash reports. The `versionCode` is the workflow's run number, so it
always goes up; the run's other box overrides it for a one-off build, for instance when a bundle
uploaded by hand got ahead of it.

Promoting to closed testing, open testing or production is done in the Play Console, after
trying the build, under the release's **Promote release** menu.

## 3. The store listing

Already in the project:

- **No data collected**: no account, no network permission, no analytics. The Data safety form's
  answers are all "No".
- The **camera** is the one permission; the photo picker and MediaStore need none.
- A launcher icon with an adaptive background, and **English and Russian** with per-app language
  settings (`locales_config.xml`).
- `targetSdk` 36, which meets Play's target API level requirement.

Still to do in the Play Console, under Grow › Store presence and Policy › App content:

- **Store listing**: short and full descriptions, a 512 × 512 icon, a 1024 × 500 feature graphic,
  and at least two phone screenshots (16:9 or 9:16, 320 to 3840 px on each side). Tablet
  screenshots too, since the app runs on tablets.
- **Privacy policy URL**: required for every app. The Privacy section of the README works as the
  text; host it somewhere public (a GitHub Pages page, or the README's own URL).
- **Data safety**, **Content rating** (the questionnaire; the app has no user content), **Target
  audience** (not designed for children), **Ads** (none), **App access** (no sign-in), **News** and
  **Government** apps (neither), **Health** (no).
- **Category**: Photography.
- A personal developer account registered since November 2023 must run a **closed test** with at
  least 12 testers for 14 days before it can apply for production access; promote the internal
  testing build to a closed track for that.
- Then Production › Create new release, pick the tested bundle, and **Send for review**. The first
  review takes a few days.

## Troubleshooting

- **"SDK location not found"**: install Android Studio or write `local.properties` as above.
  Machines that only run the shared tests and the desktop harness can skip the SDK altogether with
  `-Pagain.android=false`.
- **"Version code N has already been used"** on upload: the bundle's `versionCode` is not above
  the last one Play has. From the workflow, give the run a higher number in the `versionCode` box.
- **"Only releases with status draft may be created on draft app"** from the workflow: the app
  has not had its first release through the console yet (section 2).
- **"The Android App Bundle was not signed"**: the `ANDROID_UPLOAD_KEYSTORE` secret is missing
  or not base64, or a hand build ran without the `again.upload*` properties.
- **The camera shows "can't be opened"** in the emulator: give the virtual device a camera under
  its settings, or use a real phone.
