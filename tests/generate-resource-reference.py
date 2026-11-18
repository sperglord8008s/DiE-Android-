#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build independent AAPT2 reference APKs; never run the resulting application."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys

destination = Path(sys.argv[1])
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
aapt = sdk / "build-tools/35.0.0/aapt2"
platform = sdk / "platforms/android-35/android.jar"
if not aapt.is_file() or not platform.is_file():
    if os.environ.get("DIE_REQUIRE_AAPT2") == "true":
        raise SystemExit("Resource reference tests require Android SDK platform/build-tools 35.")
    print("AAPT2 reference fixtures not run: SDK platform/build-tools 35 unavailable.", file=sys.stderr)
    raise SystemExit(0)

def write(relative, text):
    path = destination / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

write("AndroidManifest.xml", '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="org.diemobile.resource.reference" android:versionCode="7" android:versionName="fixture">
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
 <application android:label="@string/title" android:debuggable="false" android:hasCode="false"/>
</manifest>''')
write("res/values/values.xml", '''<resources>
 <string name="title">FOSS Ω 東京 😀</string><string name="styled">plain <b>bold</b> text</string>
 <string name="xml_chars">A &amp; B &lt; C</string>
 <bool name="enabled">true</bool><integer name="count">42</integer><integer name="mask">0x1234</integer>
 <dimen name="length">-12.5dp</dimen><fraction name="ratio">75%p</fraction>
 <color name="color">#12abef</color><item type="id" name="selected"/>
 <attr name="mode" format="enum"><enum name="first" value="0"/><enum name="second" value="2"/></attr>
 <attr name="flags" format="flags"><flag name="one" value="1"/><flag name="two" value="2"/></attr>
 <string-array name="choices"><item>First Ω</item><item>@string/title</item></string-array>
 <integer-array name="numbers"><item>1</item><item>-3</item></integer-array>
 <plurals name="items"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
 <style name="FixtureStyle"><item name="android:enabled">true</item><item name="android:textColor">@color/color</item></style>
''' + "".join(f'<string name="value_{i:03}">Value {i}</string>' for i in range(80)) + "</resources>")
write("res/values-fr-rCA/values.xml", '<resources><string name="title">Communauté FOSS</string><string name="value_079">Dernière</string></resources>')
write("res/values-night-v31/values.xml", '<resources><color name="color">#ff010203</color></resources>')
write("res/xml/reference.xml", '''<reference xmlns:android="http://schemas.android.com/apk/res/android"
 xmlns:local="http://schemas.android.com/apk/res-auto" android:enabled="true" local:mode="second"
 local:flags="one|two" title="@string/title" number="-7" ratio="50%" length="2.25sp">
 <child android:id="@id/selected" text="Unicode Ω 東京 😀">Text Ω</child>
</reference>''')
compiled = destination / "compiled.zip"
subprocess.run([str(aapt), "compile", "--dir", str(destination / "res"), "-o", str(compiled)], check=True)
normal = destination / "reference.apk"
subprocess.run([str(aapt), "link", "-I", str(platform), "--manifest", str(destination / "AndroidManifest.xml"), "-o", str(normal), str(compiled)], check=True)
sparse = destination / "sparse.apk"
subprocess.run([str(aapt), "optimize", "--force-sparse-encoding", "--collapse-resource-names", "--deduplicate-entry-values", "-o", str(sparse), str(normal)], check=True)
for apk in (normal, sparse):
    expected = {}
    for name in ("AndroidManifest.xml", "res/xml/reference.xml"):
        tree = subprocess.check_output([str(aapt), "dump", "xmltree", str(apk), "--file", name], text=True)
        expected[name] = {"startElements": len(re.findall(r"^\s*E:", tree, re.M)), "attributes": len(re.findall(r"^\s*A:", tree, re.M))}
    apk.with_suffix(".reference.json").write_text(json.dumps(expected, indent=2) + "\n")
print(subprocess.check_output([str(aapt), "version"], text=True).strip())
