
# Dex+

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/udunuss/dexplus?label=Release)](https://github.com/udunuss/dexplus/releases/latest)
[![Platform](https://img.shields.io/badge/One%20UI-8.5%20%2F%20Android%2016-1f6feb.svg)](https://github.com/udunuss/dexplus)

> [!CAUTION]
> Untested with Samsung DeX on external monitors. Using this module may break external DeX functionality.

**Dex+** is an LSPosed module designed for your device's built-in display. It tries to bring classic DeX features to the newer One UI desktop mode—replacing the internal taskbar with the external taskbar, restoring the classic notification and quick settings panels, improving the home-screen layout for desktop use, and adding extra window controls.

Developed against MinsOS 1.2.3 (One UI 8.5) on a Galaxy Tab S7+. Other One UI versions or devices may differ.

<img width="1514" height="1119" alt="general" src="https://github.com/user-attachments/assets/30daf46a-2851-4a49-be9f-5df1cd3836aa" />


https://github.com/user-attachments/assets/f60b2ff0-8023-46e8-9802-8d3bbb132ee5


## Options

### General

- **Enable Dex+.** Master switch for the desktop taskbar, workspace, status bar, keyboard hook, and window controls. Disabling it restores window alphas, corner radii, and caption controls cleanly.
- **Keep Desktop Mode Always Active.** Keeps the desktop taskbar and window features on the built-in display even when DeX mode is turned off.
- **Hide Physical Keyboard Toolbar.** Hides the floating keyboard toolbar when a hardware keyboard is connected.
- **Restart Home Screen.** Reloads One UI Home.
- **Restart System UI.** Reloads System UI so title-bar changes start fresh.
- **Restore Defaults.** Prompts for confirmation and resets all preferences back to their default values across hooked processes.

### Taskbar & Panels

- **Enable Desktop Taskbar.** Shows the desktop taskbar with running apps, a notification count, quick settings, calendar, and audio controls. Can be toggled independently without turning off window controls or home-screen options.
- **Use Standard Notification & Quick Settings Shade.** Taskbar notification and quick settings buttons open the full system shade instead of the compact popups.
- **Show Search Button on Taskbar.** Shows or hides the Finder search button in the taskbar status area live.
- **Apply Scaling to External Displays.** Also applies the taskbar icon and density scale on connected external monitors.
- **Taskbar Icon & Text Scale.** Size of taskbar icons and status indicators. Default 100%. Range 70% to 140%.
- **Taskbar Height.** Height of the desktop taskbar (scales `task_bar_height_dex` and adjusts workspace bottom clearance). Default 100%. Range 75% to 135%.

### Home Screen

- **Hide Page Navigation Arrows.** Hides the left and right page arrows.
- **Lock Home Screen Gestures.** Blocks the upward app-drawer swipe and the two-finger pinch edit gesture while desktop mode is on.
- **Icon size.** Home screen icon size. The grid fits as many icons as it can at that size. Default 100%. Range 60% to 160%.
- **Side Margins.** Space on the left and right of the home screen. Default 40%.
- **Top Margin.** Space above the home screen grid. Default 0%.
- **Bottom Margin.** Extra space between the icons and the taskbar. 0% sits the grid on the taskbar. Default 100%.

### Dedicated Workspace

- **Enable Dedicated Workspace.** Replaces the normal home pages and widgets with a scrollable, full-screen shortcut grid that adapts on rotation and window resize. Supports mouse/stylus right-click context menus, searchable app picker, drag-and-drop / arrange-mode reordering, keyboard navigation (`Enter`, `Delete`, `Menu`), and automatic redirection of "Add to Home" and browser/app pinned shortcuts.
- **Open Shortcuts in Resizable Windows.** Opens workspace shortcuts in freeform desktop windows.
- **Double-Click to Open Shortcuts.** The first click selects a shortcut. The second click opens it.
- **Workspace Columns.** Shortcut columns. Default 10. Range 6 to 16.
- **Workspace Rows.** Shortcut rows. Default 5. Range 4 to 10.
- **Shortcut Icon Size.** Size of workspace icons and labels. Default 100%. Range 70% to 140%.
- **Reset Shortcuts.** Prompts for confirmation and restores the default set of workspace shortcuts.

### Window Controls

- **Hide Top Status Bar.** Hides the top status bar in desktop mode so windows can use that space.
- **Drag Window to Top Edge to Maximize.** Dragging a window to the top edge maximizes it instead of leaving DeX.
- **Fullscreen inside the window.** A video fills the window it is already in. The window is not resized, and DeX stays open.
- **Allow All Apps in Resizable Windows.** Lets every installed app run in a freeform window and be resized.
- **Always-on-Top Pin Button.** Adds a pin button that keeps a window above the others.
- **Hide Fullscreen Button on Windowed Apps.** Hides the extra fullscreen button on normal and maximized windows.
- **Long-Press Maximize for Fullscreen.** Hold Maximize or Restore to enter or leave immersive fullscreen for that window.
- **Hide App Icon Dropdown Arrow.** Hides the arrow next to the app icon. The icon still opens its menu.
- **Window corner radius.** Rounds floating window corners, including the thin outline. 0 is square. Range 0 to 48 dp (with a quick **Reset** chip to return to the system default).
- **Enable Enhanced Window Menu.** Adds extra tools to the menu opened from the app icon.
- **Window Opacity Slider.** Adds a transparency slider from 20% to 100% in that menu.
- **Center & Keep Awake Buttons.** Adds buttons to center the window and keep the screen awake while it is visible.
- **Show App Info Shortcut.** Replaces Remove from workspace with a button that opens the app's system info page.
- **Default Window Opacity.** Starting opacity for windows you have not adjusted from the menu. Default 100%. Range 30% to 100%.

Changes apply as soon as you toggle them.

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

**Workspace.** Optional full-screen shortcut grid instead of the normal home pages. Supports drag-and-drop icon arranging, right-click context menus, searchable app picker, automatic "Add to Home" shortcut redirection, custom column/row counts and icon scaling, single or double click to open, and opening shortcuts in resizable windows.

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

Do not enable this module together with the external DeX module. If that module is already loaded, Dex+ skips its hooks.

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

On Termux, if `d8` is not installed, set `R8_JAR` to an `r8.jar` path; `build.sh` also checks `$PREFIX/share/java/r8.jar` and uses `$PREFIX/tmp` automatically when available. If `/system/framework/framework-res.apk` exists, the script uses it when linking resources.

## Version

`1.2.36-dex` (`versionCode` 10236)

### Highlights in `1.2.36-dex`

- **Build & Termux verification:** Automatic Termux `$PREFIX/share/java/r8.jar` and `$PREFIX/tmp` fallback in `build.sh`.
- **Launcher & Taskbar:** Independent **Enable Desktop Taskbar** gating, `task_bar_height_dex` height scaling, external display DPI toggle (`KEY_FIX_EXTERNAL_DEX_DPI`), live Finder button and non-restarting status/arrow toggles, and `WeakReference` notification badges.
- **SystemUI Window Controls:** Dynamic resource-name lookup (with fallback IDs), live **Drag Window to Top Edge to Maximize** and **Long-Press Maximize** gating, clean restoration of default corner radius & window opacity on reset/disable, and `PIN_TAG`/`BACK_TAG` touch-pass handling.
- **Framework & Honeyboard:** Live config staleness reload in `system_server` for **Allow All Apps in Resizable Windows**, and `ContentObserver`-cached effects state + module toggle check in Honeyboard.
- **Dedicated Workspace & Settings UI:** Scrollable shortcut grid sized to view bounds, orientation rebuilds, compact top-tab layout in narrow (`< 600dp`) windows, confirmation prompts on destructive resets, and UID-restricted `PrefsProvider` writes.

## License

Dex+ is released under the [MIT License](LICENSE).
