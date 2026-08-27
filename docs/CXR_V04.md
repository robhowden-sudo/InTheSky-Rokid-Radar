# v0.4 Rokid CXR transport

Transport channel: `inthesky_radar_state`

Phone:
- CXR-M `com.rokid.cxr:client-m:1.0.8`
- `CxrApi.initBluetooth(context, pairedDevice, callback)`
- Sends one Caps string containing the existing radar_state JSON with `sendCustomCmd`.

Glasses:
- Uses `CXRServiceBridge` (CXR-S bridge) and subscribes to the same channel.
- Extracts the JSON string from Caps and passes it to the existing RadarView renderer.

The OpenSky/GPS/range packet format remains unchanged.

Note: this source is prepared for the existing GitHub Actions build. Runtime success depends on CXRService availability on the glasses firmware and Rokid's Maven SDK resolving during the cloud build.
