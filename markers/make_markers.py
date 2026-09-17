#!/usr/bin/env python3
"""
Метки для отслеживания Joy-Con камерой (ArUco DICT_4X4_50). Печатать в масштабе 100%.

ID 0-3 — левый Joy-Con, ID 4-7 — правый. Порядок: слева, середина, справа, сверху.
Размер стороны чёрного квадрата — MARKER_MM, его же знает PhoneXR (JoyConMarkers.MARKER_SIZE_M).
"""
import os
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

MARKER_MM = 25
DPI = 300
PLACES = ["слева", "середина", "справа", "сверху"]
OUT = os.path.dirname(os.path.abspath(__file__))


def mm(value):
    return int(round(value / 25.4 * DPI))


def main():
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    side = mm(MARKER_MM)
    cell_w, cell_h = mm(MARKER_MM + 20), mm(MARKER_MM + 22)
    page = Image.new("RGB", (mm(210), mm(297)), "white")
    draw = ImageDraw.Draw(page)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", mm(3.2))
        title = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", mm(5))
    except OSError:
        font = title = ImageFont.load_default()
    draw.text((mm(15), mm(12)), f"PhoneXR: метки Joy-Con (печать 100%, квадрат {MARKER_MM} мм)", fill="black", font=title)
    for hand, name, y0 in ((0, "Левый Joy-Con", 30), (1, "Правый Joy-Con", 110)):
        draw.text((mm(15), mm(y0)), name, fill="black", font=title)
        for index, place in enumerate(PLACES):
            marker_id = hand * 4 + index
            image = cv2.aruco.generateImageMarker(dictionary, marker_id, side)
            marker = Image.fromarray(np.array(image)).convert("RGB")
            x = mm(15) + index * cell_w
            y = mm(y0 + 9)
            page.paste(marker, (x + mm(10), y + mm(4)))
            # Cutting guide: white quiet zone around the marker is required for detection.
            draw.rectangle((x + mm(6), y, x + mm(14) + side, y + mm(8) + side), outline=(170, 170, 170), width=2)
            draw.text((x + mm(6), y + mm(10) + side), f"ID {marker_id} · {place}", fill="black", font=font)
            Image.fromarray(np.array(image)).save(os.path.join(OUT, f"marker_{marker_id}.png"))
    page.save(os.path.join(OUT, "joycon_markers_A4.png"), dpi=(DPI, DPI))
    page.save(os.path.join(OUT, "joycon_markers_A4.pdf"), resolution=DPI)
    print("Готово:", os.path.join(OUT, "joycon_markers_A4.pdf"))


if __name__ == "__main__":
    main()
