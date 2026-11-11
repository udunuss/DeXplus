#!/usr/bin/env python3
"""Illustrations of Dex+ features. Demo apps only: Continuum, Dex+, Settings."""

import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "screenshots")
FONT = "/system/fonts/Roboto-Regular.ttf"

BG = (18, 24, 38)
SIDEBAR = (17, 22, 33)
CARD = (23, 30, 44)
WINDOW = (28, 32, 40)
CAPTION = (36, 40, 48)
TEXT = (243, 246, 250)
MUTED = (154, 164, 178)
ACCENT = (59, 130, 246)
LINE = (255, 255, 255, 28)
TASKBAR = (16, 16, 22)


def font(size):
    return ImageFont.truetype(FONT, size)


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def icon(draw, box, kind, fill):
    x0, y0, x1, y1 = box
    rounded(draw, box, 18, fill)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    if kind == "folder":
        draw.rounded_rectangle((cx - 16, cy - 6, cx + 16, cy + 14), 4, fill=(255, 255, 255))
        draw.rectangle((cx - 16, cy - 12, cx - 2, cy - 6), fill=(255, 255, 255))
    elif kind == "gear":
        draw.ellipse((cx - 14, cy - 14, cx + 14, cy + 14), outline=(255, 255, 255), width=4)
        draw.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=(255, 255, 255))
    else:
        draw.rectangle((cx - 12, cy - 3, cx + 12, cy + 3), fill=(255, 255, 255))
        draw.rectangle((cx - 3, cy - 12, cx + 3, cy + 12), fill=(255, 255, 255))


def caption(draw, x, y, w, title, radius=18):
    rounded(draw, (x, y, x + w, y + 56), radius, CAPTION)
    draw.rectangle((x, y + 28, x + w, y + 56), fill=CAPTION)
    draw.polygon([(x + 22, y + 28), (x + 34, y + 18), (x + 34, y + 38)], fill=TEXT)
    icon(draw, (x + 52, y + 10, x + 88, y + 46), "folder" if title == "Continuum" else ("gear" if title == "Settings" else "plus"), ACCENT if title != "Settings" else (80, 90, 110))
    draw.text((x + 104, y + 16), title, font=font(22), fill=TEXT)
    buttons = ["pin", "min", "max", "close"]
    bx = x + w - 36
    for name in reversed(buttons):
        if name == "close":
            draw.line((bx - 7, y + 21, bx + 7, y + 35), fill=TEXT, width=2)
            draw.line((bx + 7, y + 21, bx - 7, y + 35), fill=TEXT, width=2)
        elif name == "max":
            draw.rectangle((bx - 8, y + 20, bx + 8, y + 36), outline=TEXT, width=2)
        elif name == "min":
            draw.line((bx - 8, y + 28, bx + 8, y + 28), fill=TEXT, width=2)
        else:
            draw.polygon([(bx - 6, y + 34), (bx, y + 20), (bx + 6, y + 34)], outline=TEXT)
        bx -= 40


def taskbar(draw, w, h, y):
    draw.rectangle((0, y, w, h), fill=TASKBAR)
    draw.rectangle((0, y, w, y + 2), fill=(90, 70, 160))
    labels = [("Continuum", "folder", (47, 111, 214)), ("Dex+", "plus", (59, 130, 246)), ("Settings", "gear", (90, 98, 114))]
    x = 36
    for label, kind, color in labels:
        icon(draw, (x, y + 14, x + 44, y + 58), kind, color)
        x += 64
    draw.text((w - 220, y + 24), "9:41", font=font(20), fill=TEXT)
    draw.rounded_rectangle((w - 120, y + 26, w - 36, y + 46), 6, outline=TEXT, width=2)
    draw.rectangle((w - 116, y + 30, w - 56, y + 42), fill=(120, 200, 140))


def wallpaper(w, h):
    image = Image.new("RGB", (w, h), (32, 48, 72))
    pix = image.load()
    for y in range(h):
        t = y / h
        r = int(32 + 20 * t)
        g = int(48 + 10 * t)
        b = int(78 - 10 * t)
        for x in range(0, w, 4):
            pix[x, y] = (r, g, b)
            if x + 1 < w:
                pix[x + 1, y] = (r, g, b)
            if x + 2 < w:
                pix[x + 2, y] = (r, g, b)
            if x + 3 < w:
                pix[x + 3, y] = (r, g, b)
    return image


def window(base, box, title, rows, radius=22):
    draw = ImageDraw.Draw(base)
    x, y, w, h = box
    rounded(draw, (x, y, x + w, y + h), radius, WINDOW)
    caption(draw, x, y, w, title, radius)
    yy = y + 78
    for row in rows:
        if row[0] == "switch":
            _, label, on = row
            draw.text((x + 24, yy), label, font=font(20), fill=TEXT)
            pill = (x + w - 84, yy, x + w - 24, yy + 28)
            rounded(draw, pill, 14, ACCENT if on else (70, 76, 88))
            knob = yy + 4
            kx = pill[2] - 24 if on else pill[0] + 4
            draw.ellipse((kx, knob, kx + 20, knob + 20), fill=(255, 255, 255))
            yy += 48
        elif row[0] == "folder":
            _, label = row
            icon(draw, (x + 24, yy, x + 56, yy + 32), "folder", (70, 130, 210))
            draw.text((x + 72, yy + 4), label, font=font(20), fill=TEXT)
            yy += 48
        elif row[0] == "text":
            _, label = row
            draw.text((x + 24, yy), label, font=font(18), fill=MUTED)
            yy += 36
        elif row[0] == "slider":
            _, label, value = row
            draw.text((x + 24, yy), label, font=font(20), fill=TEXT)
            draw.text((x + w - 110, yy), value, font=font(18), fill=MUTED)
            yy += 36
            draw.rounded_rectangle((x + 24, yy, x + w - 24, yy + 8), 4, fill=(70, 78, 92))
            draw.ellipse((x + 80, yy - 6, x + 100, yy + 14), fill=ACCENT)
            yy += 36


def desktop():
    w, h = 1600, 900
    image = wallpaper(w, h)
    window(image, (36, 28, 500, 760), "Continuum", [
        ("text", "Internal storage"),
        ("folder", "Documents"),
        ("folder", "Download"),
        ("folder", "Music"),
        ("folder", "Pictures"),
    ])
    window(image, (560, 64, 480, 700), "Settings", [
        ("text", "Display"),
        ("switch", "Dark mode", True),
        ("text", "Font size    Medium"),
        ("text", "Screen timeout    30 seconds"),
    ])
    window(image, (1060, 24, 500, 780), "Dex+", [
        ("text", "Window Controls"),
        ("switch", "Pin button", True),
        ("switch", "Long-press fullscreen", True),
        ("slider", "Corner radius", "16 dp"),
        ("slider", "Default opacity", "100%"),
    ])
    taskbar(ImageDraw.Draw(image), w, h, 820)
    return image


def settings_page():
    w, h = 1400, 860
    image = Image.new("RGB", (w, h), BG)
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 320, h), fill=SIDEBAR)
    draw.text((28, 28), "Dex+", font=font(28), fill=TEXT)
    tabs = [
        ("General", False),
        ("Taskbar & Panels", False),
        ("Home Screen", False),
        ("Dedicated Workspace", False),
        ("Window Controls", True),
    ]
    y = 100
    for name, selected in tabs:
        if selected:
            rounded(draw, (16, y, 304, y + 64), 12, (32, 48, 78))
        draw.text((32, y + 18), name, font=font(18), fill=TEXT if selected else MUTED)
        y += 72
    draw.text((360, 36), "Window Controls", font=font(32), fill=TEXT)
    draw.text((360, 80), "Title bar buttons, pinning, menu tools and sizing", font=font(16), fill=MUTED)
    cards = [
        ("Always-on-Top Pin Button", True),
        ("Hide Fullscreen Button on Windowed Apps", True),
        ("Long-Press Maximize for Fullscreen", True),
        ("Hide App Icon Dropdown Arrow", True),
        ("Window Opacity Slider", True),
        ("Center and Keep Awake Buttons", True),
        ("Show App Info Shortcut", True),
    ]
    y = 140
    for label, on in cards:
        rounded(draw, (360, y, 1360, y + 72), 14, CARD)
        draw.text((384, y + 24), label, font=font(20), fill=TEXT)
        pill = (1240, y + 22, 1324, y + 50)
        rounded(draw, pill, 14, ACCENT if on else (70, 76, 88))
        kx = 1296 if on else 1244
        draw.ellipse((kx, y + 26, kx + 20, y + 46), fill=(255, 255, 255))
        y += 84
    return image


def home():
    w, h = 1600, 900
    image = wallpaper(w, h)
    draw = ImageDraw.Draw(image)
    items = [
        (280, 280, "Continuum", "folder", (47, 111, 214)),
        (640, 280, "Dex+", "plus", (59, 130, 246)),
        (1000, 280, "Settings", "gear", (90, 98, 114)),
    ]
    for x, y, label, kind, color in items:
        icon(draw, (x, y, x + 84, y + 84), kind, color)
        tw = draw.textlength(label, font=font(18))
        draw.text((x + 42 - tw / 2, y + 96), label, font=font(18), fill=TEXT)
    taskbar(draw, w, h, 820)
    return image


def main():
    os.makedirs(OUT, exist_ok=True)
    desktop().save(os.path.join(OUT, "desktop.png"), optimize=True)
    settings_page().save(os.path.join(OUT, "settings.png"), optimize=True)
    home().save(os.path.join(OUT, "home.png"), optimize=True)
    print(OUT)


if __name__ == "__main__":
    main()
