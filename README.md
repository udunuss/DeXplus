# Dex+

Dex+ is an LSPosed module for Samsung One UI desktop mode on the built-in display. It keeps the desktop taskbar, status panels, home-screen layout, and window controls available without leaving DeX for a normal tablet shell.

Developed against One UI 8.5 on a Galaxy Tab. Other One UI versions can differ.

Package name: `com.udunus.dexplus`.

## AI disclaimer

Most of this project was written with help from AI coding tools, then tried on a Samsung tablet running One UI desktop mode.

- The hooks follow Samsung and Android internal code that is not a public API. A system update can break them.
- Some behavior was tuned from what showed up on one device, not from a full test matrix.
- Comments and names in the code can lag behind what the settings screen actually does.
- Nothing here is reviewed or supported by Samsung, Google, or the LSPosed project.

Read the code before you install it. It runs inside One UI Home, System UI, the Samsung keyboard, and the Android framework. Use a device you can recover.

## What it does

**Taskbar.** Shows the desktop taskbar on the built-in display, with a notification count, quick settings, calendar, and audio controls. Taskbar icon scale and height are adjustable. Notification and quick settings buttons can open the compact DeX panels or the normal system shade. Panel sizing uses the tablet density rather than an external-monitor density.

**Home screen.** Icon size picks the grid. Side, top, and bottom margins are adjustable. Bottom margin at 0% sits the icon grid on the taskbar. Page arrows and home-screen gestures can be locked while desktop mode is on.

**Workspace.** Optional full-screen shortcut grid instead of the normal home pages. Column and row counts, icon size, single or double click to open, and opening apps in resizable windows.

**Windows.** Title-bar back button for that window only. Pin to keep a window above others. Drag a maximized window by its title bar to restore it and keep moving it. Drag to the top edge can maximize instead of leaving DeX. Long-press maximize for immersive fullscreen. Fullscreen requests from an app can stay inside the current window. App-icon menu can include opacity, center, keep-awake, and app info. Window corner radius applies to floating windows and the thin outline. Default opacity applies to windows you have not adjusted individually.

**Status bar.** The top status bar can be hidden in desktop mode so windows use that space.

The clock and date popup on the taskbar is not given the same density treatment as the notification and quick settings panels. It can still look small.

## Install

1. Open the [latest release](https://github.com/udunuss/dexplus/releases/latest) and download the `DexPlus-*.apk` file.
2. Install the APK.
3. In LSPosed, enable Dex+ for:
   - System framework (`android`)
   - System UI (`com.android.systemui`)
   - One UI Home (`com.sec.android.app.launcher`)
   - Samsung Keyboard (`com.samsung.android.honeyboard`), if you use it
4. Reboot.

Do not enable this module in the same process as `com.minsos.externaldex`. If that module is loaded, Dex+ skips its hooks.

After the first reboot, the in-app restart buttons reload One UI Home and System UI when you change settings. They do not need root.

## Releases

Pushing a tag named `v*` (for example `v1.2.35`) runs GitHub Actions. The workflow builds a signed APK and attaches it to a GitHub Release. The signing keystore is not in this repository. Actions reads it from repository secrets:

- `KEYSTORE_BASE64` is the keystore file, base64-encoded
- `KEYSTORE_PASSWORD` is the keystore password
- `KEY_ALIAS` is the key alias inside the keystore
- `KEY_PASSWORD` is that key's password

## Build on your own machine

You need Java 17, Android SDK platform 34, build-tools that provide `aapt2`, `d8`, `zipalign`, and `apksigner`, and your own keystore.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
export KEYSTORE=/path/to/your-keystore.jks
export KEY_ALIAS=your_alias
export KEYSTORE_PASSWORD=your_store_password
export KEY_PASSWORD=your_key_password
chmod +x build.sh
./build.sh
```

The APK is written to `DexPlus-<version>.apk` when `FINAL_APK` is set, and to `DexPlus.apk` otherwise.

On Termux, if `d8` is not installed, set `R8_JAR` to an R8 jar. If `/system/framework/framework-res.apk` exists, the script uses it when linking resources.

## Version

`1.2.35-dex` (versionCode 10235).

## License

No license is set. Ask before you reuse the code.
