# Again on iPhone

The iOS app is the same Compose UI as Android, in a small SwiftUI wrapper (`iosApp/`). The camera
is AVFoundation (`composeApp/src/iosMain/.../IosCameraSession.kt`), the photo picker is PHPicker,
saving goes through PhotoKit with add-only access, and sharing uses the system share sheet.

## What you need

- A Mac with **Xcode** (from the App Store) and a **JDK 17+**. Any of these works: the
  [Temurin](https://adoptium.net) installer, Android Studio (its bundled JDK is picked up), or
  `brew install openjdk@17`.
- An **Apple ID**. A free one is enough to put the app on your own iPhone. TestFlight and the App
  Store need a paid [Apple Developer Program](https://developer.apple.com/programs/) membership.

## 1. Install on your iPhone

1. Xcode › Settings › Accounts: add your Apple ID. A free account shows up as a "Personal Team".
2. Put the team's ID in `iosApp/Configuration/Config.xcconfig`:
   ```
   TEAM_ID=ABCDE12345
   ```
   The ID is on [developer.apple.com/account](https://developer.apple.com/account) under Membership
   details. With a free account, leave `TEAM_ID` empty and pick the Personal Team under
   Signing & Capabilities in Xcode.
3. Open `iosApp/iosApp.xcodeproj`. Connect the iPhone with a cable, unlock it, tap **Trust**, and
   choose it as the run destination at the top of the window.
4. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (it restarts the phone).
5. Press **Run** (⌘R). The first build takes a few minutes, because Xcode compiles the Kotlin
   framework through Gradle.
6. With a free account the first launch is blocked. On the iPhone, go to Settings › General ›
   VPN & Device Management, open your Apple ID and tap **Trust**.

With a free account the app stops opening after **7 days**; press Run again to renew it. A paid
membership makes that a year, and TestFlight makes it 90 days per build.

If Xcode says the bundle identifier `sh.gerra.again` is unavailable, change `BUNDLE_ID` in
`Config.xcconfig` to something of your own, e.g. `com.yourname.again`.

## 2. TestFlight

Once you have a paid membership:

1. In [App Store Connect](https://appstoreconnect.apple.com) › Apps › **+** › New App: platform iOS,
   name **Again Camera: Then & Now**, primary language English, bundle ID `sh.gerra.again` (Xcode
   registers it the first time it signs with your paid team; it also appears under Certificates,
   Identifiers & Profiles). The SKU can be anything, e.g. `again`.
2. In Xcode choose **Any iOS Device (arm64)** as the destination, then Product › **Archive**.
3. In the Organizer that opens: **Distribute App** › **TestFlight & App Store**. Xcode signs,
   uploads and validates the build.
4. After processing (usually 5–30 minutes), the build shows up under TestFlight in App Store
   Connect. Add yourself as an internal tester, then install the **TestFlight** app on the iPhone
   and accept the invite.

Every upload needs a new build number: raise `CURRENT_PROJECT_VERSION` in `Config.xcconfig`
(1, 2, 3…). Raise `MARKETING_VERSION` (1.0, 1.1…) for each App Store release.

The app already declares that it uses no non-exempt encryption (`ITSAppUsesNonExemptEncryption`),
so TestFlight does not ask about export compliance.

## 3. App Store

Already in the project:

- **Privacy manifest** (`PrivacyInfo.xcprivacy`): no tracking, no data collected, and the reasons
  for the system APIs the Kotlin runtime and Skia use.
- The **camera** and **add to photo library** permission texts. The old photo comes through the
  system picker, which needs no permission.
- A 1024 × 1024 **app icon** with no transparency.
- **English and Russian**, and every orientation on iPad (needed for iPad multitasking).

Still to do in App Store Connect:

- **App Privacy**: "Data Not Collected".
- **Age rating**: answer "None" throughout (4+).
- **Category**: Photo & Video.
- **Screenshots**: 6.9" iPhone (1320 × 2868) are required; 13" iPad (2064 × 2752) too, since the app
  runs on iPad. Take them in the simulator with ⌘S.
- **Privacy policy URL**: required for every app. The Privacy section of the README works as the
  text; host it somewhere public (a GitHub Pages page, or the README's own URL).
- **Support URL**: e.g. the repository's Issues page.
- Description, keywords and a subtitle, then pick the TestFlight build and **Submit for Review**.

## Troubleshooting

- **"Unable to locate a Java Runtime"** during the Kotlin build phase: install a JDK as above.
  The build phase also looks for Android Studio's and Homebrew's.
- **"No such module 'ComposeApp'"** in the editor before the first build: build once (⌘B), and
  Xcode finds the framework Gradle made.
- **The camera shows "can't be opened"**: another app may be using it (on iPad, in Split View),
  or the device has no camera, as in the simulator. Use a real iPhone.
