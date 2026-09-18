#!/usr/bin/env python3
"""Packs the PhoneXR VR mod for Minecraft Bedrock into a .mcaddon that PhoneXR installs itself.

  python3 minecraft-mod/build_mod.py   →  app/src/main/assets/minecraft/PhoneXR-VR.mcaddon
"""
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "minecraft", "PhoneXR-VR.mcaddon")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as addon:
    for folder, name in (("behavior", "PhoneXR VR BP"), ("resource", "PhoneXR VR RP")):
        base = os.path.join(HERE, folder)
        for directory, _, files in os.walk(base):
            for file in files:
                path = os.path.join(directory, file)
                addon.write(path, os.path.join(name, os.path.relpath(path, base)))
print("Готово:", OUT, os.path.getsize(OUT), "байт")
