# GeoPort USB / iOS 26 baseline

This local adaptation targets macOS + USB, using pymobiledevice3 11.30.0.
It does not claim iOS 27 compatibility. Wi-Fi, GPX playback, fuel pricing, and
keyboard movement are not included in the baseline UI. Original sources remain
in `src/legacy_main.py` and `src/templates/map.html` / `map2.html`.

## Run

```sh
cd /Users/chessman/Downloads/GeoPort-main
source .venv-ios26/bin/activate
python -m pip install -r requirements-ios26.txt
python src/main.py --no-browser --port 54321
```

Open http://127.0.0.1:54321. No sudo is needed. Stop other location tools first.
Unlock the phone, trust the Mac, and manually enable Developer Mode.
Select the USB device and connect. Initial preparation may download a DDI.
Select a point or enter latitude/longitude, then press Set Location.
Stop Location clears simulation while preserving the same session. Disconnect
clears before closing the session. Closing the browser does not clear simulation.
Ctrl+C / SIGTERM attempts to clear before shutdown; force-kill, cable removal,
or a dead service cannot guarantee cleanup. Reconnect and clear explicitly.

The server binds only to localhost and does not send device telemetry. Leaflet
assets and OpenStreetMap tiles are fetched from external servers by the browser.
Coordinates can still be entered if the map cannot load.

## Acceptance on the phone

Record model, iOS build, macOS and dependency versions. Check the current-location
blue dot in Apple Maps, not a search pin. The app reports command completion,
not independent verification of the actual location delivered to phone apps.

1. Connect from a fresh phone boot without first running CLI auto-mount.
2. Set point A, then a distinct point B; verify both on the phone.
3. Clear while connected; confirm return to the real position.
4. Repeat setting and clearing three times.
5. Disconnect normally, reconnect, set and clear again.
6. Unplug while connected. Expect an explicit warning; plug in, reconnect, clear.
7. While simulating, Ctrl+C the server; confirm recovery on the phone.

Do not label all iOS 26 builds supported based on one device. iPhone 16e / iOS
26.6.2 (23G90) CLI set and clear were verified by the user; the integrated app's
location changes and recovery require separate phone observation.

## Local checks

```sh
.venv-ios26/bin/python src/main.py --help
.venv-ios26/bin/python -m unittest discover -s tests -v
```

The tests mock hardware and cover clear-before-close ordering, retry after clear
failure, coordinate validation, repeated set failure, unplug cleanup, and HTTP
error/origin handling. They are not a substitute for device testing.

## Integration check performed

The new page was opened on localhost and detected the attached iPhone 16e running
26.6.2. Connecting through the new controller succeeded. Clear-and-disconnect
returned successfully and the page returned to its disconnected state. No new
simulated coordinate was sent during this integration check; actual A/B position
changes and restoration still need user observation on the phone.
