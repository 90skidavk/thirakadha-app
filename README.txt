THIRAKKATHA - ANDROID APP
=========================

This folder is the complete Android app. GitHub builds the APK for you,
free, in about 3 minutes. You do not need Android Studio.

GET THE APK (do this on a PC)
  1. Sign in at github.com and create a new repository
     (name: thirakkatha-android, Public).
  2. On the new repository page click "uploading an existing file".
  3. Unzip this folder. Open it, press Ctrl+A to select EVERYTHING inside
     (including the .github folder) and drag it into the browser.
     Click "Commit changes".
  4. Open the "Actions" tab. A run called "Build Android APK" starts by
     itself. Wait for the green tick.
     (If Actions asks you to enable workflows, click the enable button,
      then "Run workflow".)
  5. Open the repository's "Releases" section (right side of the main page)
     and download Thirakkatha.apk. You can open that page on your phone.
  6. On the phone, open the APK. Android asks to allow installing from
     this source: allow it, then Install.

WHAT WORKS IN THE APK
  - Manglish typing, spelling choices and learning: fully offline
  - Voice typing in Malayalam (uses the phone's Google voice typing;
    needs internet, and Malayalam added in Google voice settings)
  - Save / open: Android's file screen. Pick "Drive" in its menu to save
    to or open from Google Drive
  - Print or save as PDF, read aloud (needs a Malayalam text-to-speech voice)
  - Scripts autosave inside the app

NOT IN THE APK
  - AI polish and AI writing tools (those only run inside Claude)
  - Direct Google sign-in (Google blocks it inside apps like this;
    the Drive option in the save screen replaces it)

UPDATING THE APP
  Replace app/src/main/assets/www/index.html with the new version, raise
  versionCode by 1 in app/build.gradle, and upload again. The new APK
  installs over the old one and keeps your scripts.

IF THE BUILD SHOWS A RED CROSS
  Open the failed run, copy the red error lines, and send them to Claude.

NOTE: app/thirakkatha.keystore is a shared signing key included so updates
install smoothly. Before publishing on the Play Store, create your own key.
