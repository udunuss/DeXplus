
# DeX+

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/udunuss/dexplus?label=Release)](https://github.com/udunuss/dexplus/releases/latest)
[![Platform](https://img.shields.io/badge/One%20UI-8.5%20%2F%20Android%2016-1f6feb.svg)](https://github.com/udunuss/dexplus)

> [!NOTE]
> Supports both **Internal DeX** (built-in display) and **External DeX** (connected monitors & simulated secondary displays) with independent taskbar, desktop, and DPI controls.

**DeX+** is an LSPosed module for Samsung One UI desktop mode. It brings classic DeX features to the built-in display while also offering independent External DeX customization—providing desktop taskbars with classic notification and quick settings panels, independent Windows-style / file-backed desktop workspaces for internal and external displays, per-display DPI offsets, and enhanced window controls.

Developed against MinsOS 1.2.3 (One UI 8.5) on a Galaxy Tab S7+. Other One UI versions or devices may differ.




https://github.com/user-attachments/assets/e2592c10-9f5e-465f-a000-b518c6d268b6


https://github.com/user-attachments/assets/6394d223-4b6f-4649-8b46-9fc2653cbc2d



https://github.com/user-attachments/assets/af07b760-d09a-4b91-8765-b06dca87f027



https://github.com/user-attachments/assets/a4313a31-2bb7-4126-aabb-d0ad80c24a40



## Options

The DeX+ app organizes settings into four scope-badged groups: **Overview**, **Shared**, **Internal DeX**, and **External DeX**.

### Overview

- **Enable DeX+.** Master switch for the desktop taskbar, workspace, status bar, keyboard hook, display DPI offsets, and window controls. Disabling it restores window alphas, corner radii, DPI, and caption controls cleanly.
- **Keep Desktop Mode Always Active.** Keeps the desktop taskbar and window features on the built-in display even when DeX mode is turned off.
- **Hide Physical Keyboard Toolbar.** Hides the floating keyboard toolbar when a hardware keyboard is connected.
- **Advanced Power Menu.** Adds Reboot, Soft Reboot, Recovery, and Bootloader actions to the power menu.
- **Connected Displays Status.** Live summary of built-in and active external displays.
- **Restart Home Screen / Restart System UI / Restore Defaults.** Quick maintenance actions (restarts do not require root).

### Shared · Window Controls

Applies across all displays:

- **Hide Top Status Bar.** Hides the top status bar in desktop mode so windows can use that space.
- **Drag Window to Top Edge to Maximize.** Dragging a window anywhere along the top edge (left, center, or right, with no center dead zone) maximizes it instead of leaving DeX. Maximized windows automatically get square corners (`0` radius).
- **Fullscreen inside the window.** A video fills the window it is already in without resizing it. When turned off, system fullscreen stays inside DeX and cleanly restores desktop windows, icons, and wallpaper on exit.
- **Allow All Apps in Resizable Windows.** Lets every installed app run in a freeform window and be resized.
- **Always-on-Top Pin Button.** Adds a pin button that keeps a window above others, including while dragging unpinned windows. Unpinning an unfocused window tucks it cleanly right behind the active window.
- **Hide Fullscreen Button on Windowed Apps.** Hides the extra fullscreen button on normal and maximized windows.
- **Long-Press Maximize for Fullscreen.** Hold Maximize or Restore to enter or leave immersive fullscreen for that window.
- **Hide App Icon Dropdown Arrow.** Hides the arrow next to the app icon. The icon still opens its menu.
- **Window corner radius.** Rounds floating window corners, including the thin outline. 0 is square. Range 0 to 48 dp (with **Reset**).
- **Enable Enhanced Window Menu.** Adds extra tools to the menu opened from the app icon:
  - **Window Opacity Slider** (20%–100%)
  - **Center & Keep Awake Buttons**
  - **Show App Info Shortcut**
- **Default Window Opacity.** Starting opacity for windows you have not adjusted individually. Default 100%. Range 30% to 100% (with **Reset**).

### Internal DeX

- **Taskbar & Panels:**
  - **Enable Desktop Taskbar.** Shows the desktop taskbar with running apps, live notification count badge, quick settings, calendar, and audio controls on the built-in display.
  - **Use Standard Notification & Quick Settings Shade.** Opens the full pull-down system shade instead of compact popups.
  - **Show Search Button on Taskbar.** Shows or hides the Finder search button in the built-in taskbar status area live.
  - **Taskbar Icon & Text Scale.** 70%–140% (Default 100%, with **Reset**).
  - **Taskbar Height.** 75%–135% (Default 100%, with **Reset**).
- **Desktop:**
  - **Copy Settings from External Desktop.** Copies workspace toggles, folder path, `"New"` menu file types, and icon size from External Desktop.
  - **Open Shortcuts in Resizable Windows / Free Grid Placement / Double-Click to Open Shortcuts.** Independent workspace behavior toggles.
  - **File-Based Desktop & Desktop Folder Path.** Uses a real storage folder (default `/storage/emulated/0/Desktop`) with `.dexsc` shortcuts, folders, APKs, media, and documents.
  - **Custom `"New"` Menu File Types.** Add, remove, or reset custom file templates shown under Desktop right-click → **New**.
  - **Desktop Icon Size.** 70%–140% square-cell grid scaling (Default 100%, with **Reset**).
  - **Create Classic Icons in Desktop Folder / Reset Internal Desktop Shortcuts.**
- **Display & DPI:**
  - **Internal DeX DPI Offset.** Signed modifier (`-120` to `+120 dpi`, default `0`) added to or subtracted from the built-in display's original density while Internal DeX is active, affecting apps, windows, and system UI.

### External DeX

Applies strictly to connected monitors and simulated secondary displays without leaking onto the built-in display:

- **External Taskbar:**
  - **Show Search Button on External Taskbar.** Independent Finder button toggle.
  - **External Taskbar Icon & Text Scale.** 70%–140% (Default 100%, with **Reset**).
  - **External Taskbar Height.** 75%–135% (Default 100%, with **Reset**).
- **External Desktop:**
  - **Enable Custom Desktop on External Displays.** Runs an independent instance of the DeX+ desktop workspace on external monitors, launching apps directly onto the external display.
  - **Copy Settings from Internal Desktop.** One-tap profile copy from Internal Desktop.
  - **Full Independent Settings.** Independent resizable-window launch, free grid placement, double-click, file-based desktop, custom folder path, `"New"` menu file types, icon size, and shortcut management.
  - **Shared Folder Horizontal Grid Sync.** When both Internal and External Desktops use File-Based mode with the same folder, the narrower desktop automatically adapts its square grid and icon sizing to match the wider desktop's horizontal column count, syncs icon moves instantaneously across displays, and grays out the synced desktop's icon size slider while preserving its saved preference if you later switch folders.
- **External Display & DPI:**
  - **External DeX DPI Offset.** Signed modifier (`-160` to `+160 dpi`, default `0`) applied directly to the external display's original density, affecting apps, windows, and UI independently of the built-in display.

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

`1.3.0-dex` (`versionCode` 10300)

### Highlights in `1.3.0-dex`

- **Redesigned DeX+ Settings UI:** Rebuilt with a grouped sidebar navigation rail (**Overview**, **Shared**, **Internal DeX**, **External DeX**), custom vector icons, scope badges, live connected-display status, and dedicated **Display & DPI** sections.
- **Full External DeX Support & Isolation:** Scoped taskbar, display-context, app-launch, right-click, and Finder/panel hooks so connected monitors and simulated secondary displays work natively without leaking into the built-in display, plus automatic live-wallpaper recovery when disconnecting a secondary display.
- **Independent Internal & External Desktop Instances:** Both displays now run full independent instances of the DeX+ desktop workspace with their own settings, shortcut stores, `"New"` menu file types, and one-tap **Copy Settings** buttons. When both use File-Based Desktop with the same folder, horizontal grid counts and icon moves sync live across displays and the narrower desktop's icon slider is gracefully locked while preserving its saved preference.
- **Per-Display Signed DPI Offsets:** Replaced the old DPI override toggle with independent signed DPI offset sliders (`-120..+120` Internal, `-160..+160` External) applied via `WindowManagerService` so apps, windows, and UI scale cleanly on each display.
- **Square Desktop Grid, Top-Edge Maximize & Pin Polish:** Switched desktop grids to proportional square cells at all icon sizes, eliminated the center-top maximize dead zone, kept pinned windows above elevated dragged windows, and refined unpinning unfocused windows to tuck cleanly behind the active window.

### Highlights in `1.2.47-dex` – `1.2.51-dex`

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
