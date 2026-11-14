#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
# The user must complete Xcode's license and first-launch setup beforehand.
xcodebuild -version
xcodebuild -project Locus.xcodeproj -scheme Locus -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
python3 - <<'PY'
from pathlib import Path
import zipfile
app=Path('build/DerivedData/Build/Products/Release-iphoneos/Locus.app')
if not app.is_dir():
    raise SystemExit('Build did not produce Locus.app')
out=Path('outputs');out.mkdir(exist_ok=True)
with zipfile.ZipFile(out/'GeoPort-Mobile-unsigned.ipa','w',zipfile.ZIP_DEFLATED) as archive:
    for file in app.rglob('*'):
        if file.is_file(): archive.write(file,Path('Payload/Locus.app')/file.relative_to(app))
print('Unsigned IPA: outputs/GeoPort-Mobile-unsigned.ipa; sign before installation.')
PY
