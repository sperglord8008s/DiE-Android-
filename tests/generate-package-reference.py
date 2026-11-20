#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Independent SDK 35 compiled split declarations; resulting APKs are never installed."""
from pathlib import Path
import json
import os
import subprocess
import sys

destination = Path(sys.argv[1])
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
aapt = sdk / "build-tools/35.0.0/aapt2"
platform = sdk / "platforms/android-35/android.jar"
if not aapt.is_file() or not platform.is_file():
    if os.environ.get("DIE_REQUIRE_AAPT2") == "true":
        raise SystemExit("Package declaration references require Android platform/build-tools 35.")
    print("Package AAPT2 references not run: pinned SDK unavailable.", file=sys.stderr)
    raise SystemExit(0)

destination.mkdir(parents=True, exist_ok=True)
fixtures = {
    "community": ("", "", ""),
    "base": ("", 'android:isolatedSplits="true" android:isSplitRequired="true" android:requiredSplitTypes="language,density"', ""),
    "core": ('split="feature.core"', 'android:isFeatureSplit="true"', ""),
    "notes": ('split="feature.notes"', 'android:isFeatureSplit="true"', '<uses-split android:name="feature.core"/>'),
    "language": ('split="config.en" configForSplit="feature.notes"', 'android:splitTypes="language"', ""),
    "density": ('split="config.xhdpi"', 'android:splitTypes="density"', ""),
    "missing": ('split="feature.missing"', 'android:isFeatureSplit="true"', '<uses-split android:name="feature.absent"/>'),
    "duplicate-notes": ('split="feature.notes"', 'android:isFeatureSplit="true"', '<uses-split android:name="feature.core"/>'),
}
for name, (identity, flags, children) in fixtures.items():
    package = "com.aura.oobe.motorola" if name == "community" else "org.diemobile.split.reference"
    manifest = destination / (name + ".xml")
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="{package}" android:versionCode="2147483647"
 android:versionCodeMajor="2" android:versionName="fixture Ω" {identity} {flags}>
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
 {children}<application android:hasCode="false"/>
</manifest>''', encoding="utf-8")
    output = destination / (name + ".apk")
    subprocess.run([str(aapt), "link", "-I", str(platform), "--manifest", str(manifest), "-o", str(output)], check=True)
    tree = subprocess.check_output([str(aapt), "dump", "xmltree", str(output), "--file", "AndroidManifest.xml"], text=True)
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(output)], text=True)
    (destination / (name + ".reference.json")).write_text(json.dumps({"aaptXmlTree": tree, "aaptBadging": badging}, indent=2) + "\n")
print(subprocess.check_output([str(aapt), "version"], text=True).strip())
