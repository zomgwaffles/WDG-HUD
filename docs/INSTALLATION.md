# Installation — watch + Android companion

**Status:** Source-build instructions for the current development tree, not a verified public release installer. Windows/Android is the tested developer workflow. Follow both halves; the watch by itself does not fetch WDGWars data.

## 1. Hardware and prerequisites

- **Watch:** Waveshare ESP32-S3 Touch AMOLED 2.06-inch watch/development board with the project's supported CO5300 display and original pin wiring.
- **Phone:** Android device with Bluetooth Low Energy, GPS/location services, and network connectivity for initial WDGWars authentication.
- **Computer:** Git, VS Code with PlatformIO, Android Studio with the SDK/platform matching `compileSdk 37`, and a JDK usable by the project Gradle wrapper (its daemon toolchain requests Java **25**). Android Studio's bundled runtime may work when properly selected.
- **USB:** Data-capable cable for flashing; enable Developer Options/USB debugging on the Android test phone to install from a computer.
- **Account:** WDGWars API key, obtained from your own WDGWars account. Never put it in source code, a build configuration, a screenshot, or an issue.

Current Android minimum SDK is **26**, but successful compilation alone is not proof the app has been validated on every supported OS version.

## 2. Clone

```powershell
git clone https://github.com/zomgwaffles/WDG-HUD.git
cd .\WDG-HUD
git status
```

Both `WDGHeadsUp` and `WDGHUD-Android` are part of **one** Git repository. Do not overlay old release ZIPs on these source trees.

## 3. Flash the watch

Open `WDGHeadsUp` as a PlatformIO project in VS Code, or use a PlatformIO CLI:

```powershell
cd .\WDGHeadsUp
pio run -e esp32-s3-devkitc-1
pio run -e esp32-s3-devkitc-1 -t upload
```

If a serial upload port is not detected, connect the powered watch using a known data cable and check Device Manager. To specify a detected port, append `--upload-port COMxx` with your actual COM port. A build succeeding but an upload failing means the firmware may **not** have reached the watch.

Verify that the watch boots, displays the 3×3 HUD, and responds to touch. Do not erase flash/NVS just to diagnose a build issue; NVS stores user snapshots and settings.

## 4. Build and install Android

In a new PowerShell terminal from the repository root:

```powershell
cd .\WDGHUD-Android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Alternatively open `WDGHUD-Android` in Android Studio, sync Gradle, select your connected phone, and click **Run**. The generated debug APK is normally under `app/build/outputs/apk/debug/`. No signed public release APK is provided at this stage.

**Java/Gradle troubleshooting:** If PowerShell reports `JAVA_HOME is not set`, configure Gradle JDK in Android Studio or set `JAVA_HOME` to the *actual* installed JDK directory for that terminal. For example:

```powershell
$env:JAVA_HOME = "C:\Path\To\Your\JDK"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

The JDK example is a placeholder, not a universal installation path. This repository currently asks Gradle to provision Java 25 through its daemon toolchain configuration.

## 5. Phone permissions, WDGWars account and pairing

1. Launch the Android companion. Permit Bluetooth scan/connect, precise location, and notifications where requested. Keep phone location services enabled. Android limits background location/foreground-service behavior depending on OS version and user restrictions.
2. Under **WDGWARS ACCESS**, enter your account's **WDGWARS // ACCESS KEY** and choose **VERIFY UPLINK**.
3. For authenticated web-based sector-detail fetching, choose **LOGIN TO WDGWARS WEBSITE** and sign in using the website dialog. This is separate from supplying the API key. Close the dialog when finished.
4. Turn on the watch. The app discovers BLE advertising named **WDG HUD** and attempts to connect. On first pairing, read the **six-digit PIN on the watch** and enter it in the Android system Bluetooth pairing prompt.
5. When the link becomes ready, **DRIVING SESSION** should show **SESSION ACTIVE** without pressing START. GPS becomes the source for center/heading updates; the Android foreground service takes over the connection when the Activity is hidden.
6. Optionally save your own `https://wdgwars.pl/card/...svg` address under **OPTIONAL // PUBLIC PROFILE CARD**. It is stored per API-key identity; it is not an API key and is not required for the HUD.

The screen can display previously validated snapshots before fresh network data arrives. An empty/unknown cell is **not** the same as a confirmed zero-AP sector.

## 6. Preserve state when upgrading

**Recommended:** install the updated debug build over the existing application. Do **not** uninstall, clear app storage, wipe watch NVS, or change the Android app ID unless intentionally testing migration. Those actions may discard saved encrypted API credentials, historical sector caches, and watch last-good snapshots.

## 7. First-use acceptance

Follow [`TESTING.md`](TESTING.md). At minimum, confirm pairing, `SESSION ACTIVE`, 3×3 updates, background GPS, reopening/reconnection, 5×5 inspection, OPERATOR/GANG, and an offline reboot that preserves last-good data. A clean first-time install on another user's devices is **not yet certified**.
