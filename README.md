
# DeX+

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/udunuss/dexplus?label=Release)](https://github.com/udunuss/dexplus/releases/latest)
[![Platform](https://img.shields.io/badge/One%20UI-8.5%20%2F%20Android%2016-1f6feb.svg)](https://github.com/udunuss/dexplus)

> [!CAUTION]
> Untested with Samsung DeX on external monitors. Using this module may break external DeX functionality.

**DeX+** is an LSPosed module designed for your device's built-in display. It tries to bring classic DeX features to the newer One UI desktop mode—replacing the internal taskbar with the external taskbar, restoring the classic notification and quick settings panels, providing a Windows-style desktop with optional file-backed folders and shortcuts, and adding extra window controls.

Developed against MinsOS 1.2.3 (One UI 8.5) on a Galaxy Tab S7+. Other One UI versions or devices may differ.




https://github.com/user-attachments/assets/e2592c10-9f5e-465f-a000-b518c6d268b6


https://github.com/user-attachments/assets/6394d223-4b6f-4649-8b46-9fc2653cbc2d



https://github.com/user-attachments/assets/af07b760-d09a-4b91-8765-b06dca87f027



https://github.com/user-attachments/assets/a4313a31-2bb7-4126-aabb-d0ad80c24a40



## Options

### General

- **Enable DeX+.** Master switch for the desktop taskbar, workspace, status bar, keyboard hook, and window controls. Disabling it restores window alphas, corner radii, and caption controls cleanly.
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
- **Taskbar Icon & Text Scale.** Size of taskbar icons and status indicators. Default 100%. Range 70% to 140% (with a **Reset** button).
- **Taskbar Height.** Height of the desktop taskbar (scales `task_bar_height_dex` and adjusts desktop bottom clearance). Default 100%. Range 75% to 135% (with a **Reset** button).


### Desktop

- **Open Shortcuts in Resizable Windows.** Opens desktop shortcuts in freeform windows. Default on.
- **Free Grid Placement.** Place and keep shortcuts anywhere on the desktop grid instead of packing them top-left (also toggleable from the desktop right-click menu). Default on.
- **Double-Click to Open Shortcuts.** The first click selects a shortcut. The second click opens it. Default on.
- **File-Based Desktop.** Uses a real folder on internal storage where each app/deep shortcut is stored as a `.dexsc` file and any folders, videos, images, APKs, or other files in that folder appear directly on the desktop. Default off.
- **Desktop Folder Path.** Storage folder used when File-Based Desktop is enabled. Default `/storage/emulated/0/Desktop`.
- **Desktop Icon Size.** Scales desktop icons and labels and automatically computes the grid columns and rows to fill available screen space. Default 100%. Range 70% to 140% (with a **Reset** button).
- **Create Classic Icons in Desktop Folder / Reset Desktop Shortcuts.** Creates classic desktop icons for file-based mode on demand, or resets desktop shortcuts back to only the System Settings icon.

### Window Controls

- **Hide Top Status Bar.** Hides the top status bar in desktop mode so windows can use that space.
- **Drag Window to Top Edge to Maximize.** Dragging a window to the top edge maximizes it instead of leaving DeX. Maximized windows automatically get square corners (`0` radius).
- **Fullscreen inside the window.** A video fills the window it is already in without resizing it. When turned off, system fullscreen stays inside DeX and cleanly restores desktop windows, icons, and wallpaper on exit.
- **Allow All Apps in Resizable Windows.** Lets every installed app run in a freeform window and be resized.
- **Always-on-Top Pin Button.** Adds a pin button that keeps a window above the others.
- **Hide Fullscreen Button on Windowed Apps.** Hides the extra fullscreen button on normal and maximized windows.
- **Long-Press Maximize for Fullscreen.** Hold Maximize or Restore to enter or leave immersive fullscreen for that window.
- **Hide App Icon Dropdown Arrow.** Hides the arrow next to the app icon. The icon still opens its menu.
- **Window corner radius.** Rounds floating window corners, including the thin outline. 0 is square. Range 0 to 48 dp (with a quick **Reset** button to return to the system default).
- **Enable Enhanced Window Menu.** Adds extra tools to the menu opened from the app icon.
- **Window Opacity Slider.** Adds a transparency slider from 20% to 100% in that menu.
- **Center & Keep Awake Buttons.** Adds buttons to center the window and keep the screen awake while it is visible.
- **Show App Info Shortcut.** Replaces Remove from workspace with a button that opens the app's system info page.
- **Default Window Opacity.** Starting opacity for windows you have not adjusted from the menu. Default 100%. Range 30% to 100% (with a **Reset** button).

Changes apply as soon as you toggle them.

## AI disclaimer

Most of this project was written with help from AI coding tools, then tried on a Samsung tablet running One UI desktop mode.

- The hooks follow Samsung and Android internal code that is not a public API. A system update can break them.
- Some behavior was tuned from what showed up on one device, not from a full test matrix.
- Comments and names in the code can lag behind what the settings screen actually does.
- Nothing here is reviewed or supported by Samsung, Google, or the LSPosed project.

Read the code before you install it. It runs inside One UI Home, System UI, the Samsung keyboard, and the Android framework. Use a device you can recover.

## What it does

**Taskbar.** Shows the desktop taskbar on the built-in display, with a live notification count badge, quick settings, battery/charging indicator, calendar, and audio controls. Taskbar icon scale and height are adjustable. Notification and quick settings buttons can open the compact DeX panels or the normal system shade. Panel sizing uses the tablet density rather than an external-monitor density.

**Desktop.** Full-screen desktop icon grid that automatically computes columns and rows to fill the screen based on **Desktop Icon Size**. Supports marquee drag-box selection, **Shift** / **Ctrl** click multi-selection, dragging single or multiple icons across the grid, drag-and-drop from the One UI App Drawer, right-click context menus (including **Free Grid Placement** toggle and **Open Desktop folder in My Files**), automatic "Add to Home" shortcut redirection, Chrome/Edge deep shortcuts, and **File-Based Desktop** (`.dexsc` shortcuts plus real folders opened directly in My Files, `.apk` files with an Android badge that launch the package installer, and media/documents).

**Windows.** Title-bar back button for that window only. Pin to keep a window above others. Drag a maximized window by its title bar to restore it and keep moving it. Drag to the top edge to maximize with square corners instead of leaving DeX. Long-press maximize for immersive fullscreen. App fullscreen requests either stay inside the window or keep DeX active and cleanly restore the desktop on exit. App-icon menu can include opacity, center, keep-awake, and app info. Window corner radius applies to floating windows and the thin outline. Default opacity applies to windows you have not adjusted individually.

**Status bar.** The top status bar can be hidden in desktop mode so windows use that space.

The clock and date popup on the taskbar is not given the same density treatment as the notification and quick settings panels. It can still look small.

## Install

1. Open the [latest release](https://github.com/udunuss/dexplus/releases/latest) and download the `DexPlus-*.apk` file.
2. Install the APK.
3. In LSPosed, enable DeX+ for:
   - System framework (`android`)
   - System UI (`com.android.systemui`)
   - One UI Home (`com.sec.android.app.launcher`)
   - Samsung Keyboard (`com.samsung.android.honeyboard`), if you use it
4. Reboot.

Do not enable this module together with the external DeX module. If that module is already loaded, DeX+ skips its hooks.

After the first reboot, the in-app restart buttons reload One UI Home and System UI when you change settings. They do not need root.

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

`1.2.47-dex` (`versionCode` 10247)

### Highlights in `1.2.47-dex`

- **Desktop Inline Rename & Creation Flow:** Replaced the rename dialog with direct inline text editing under desktop icons, fixed the white focus highlight overlay on Enter, and started inline rename with only the filename stem selected when creating new folders or files.
- **Desktop "New" Submenu & Custom File Types:** Grouped item creation under a **"New"** submenu with a right-aligned chevron arrow and tighter `8dp` menu corners, plus a **"New" Menu File Types** editor in **DeX+ Settings → Desktop** to add, remove, or reset custom file templates.
- **Drag & Drop with Folders and Samsung My Files:** Added dropping desktop files into desktop folders and two-way moving of files/folders between Desktop and Samsung My Files.
- **Bluetooth Mouse Software Keyboard Fix:** Kept the software keyboard available when only a Bluetooth/wireless mouse with a keyboard HID descriptor is connected without a real physical keyboard.

### Highlights in `1.2.45-dex` – `1.2.46-dex`

- **Browser Video Fullscreen & Square Maximized Corners:** Maximized windows now get square corners (`0` radius on decor and outline). With **Fullscreen inside the window** turned off, entering browser video fullscreen no longer exits DeX, and exiting fullscreen now immediately restores visibility of underlying desktop windows, icons, and wallpaper instead of showing a black desktop.
- **Windows-Style Desktop & File-Based Desktop:** Removed the old Home Screen customization mode and manual column/row settings so the unified **Desktop** automatically fills available space from icon size, with **Free Grid Placement** and **Double-Click to Open Shortcuts** enabled by default and fresh installs starting with only the **Settings** icon. Added drag-box selection, **Shift**/**Ctrl** multi-select and multi-icon drag, right-click **Free Grid Placement** toggle, optional **File-Based Desktop** (`.dexsc` shortcuts plus real files, folders opening directly inside My Files, and `.apk` files with an Android badge that open the package installer), and **Reset** buttons on every slider.
- **Taskbar & Context Menu Fixes:** Fixed notification count badge not updating until first click, prevented battery icon shrinking on dark-mode toggle or charging animation, and removed redundant context menu actions.

## License

DeX+ is released under the [MIT License](LICENSE).
