# In The Sky Radar Bluetooth protocol v1

Transport: Classic Bluetooth RFCOMM, newline-delimited UTF-8 JSON.

Service UUID: `9d9a9c20-a3cc-4a20-b5a2-34f5f6b8c701`

Phone sends one `radar_state` object after each OpenSky update (nominally every 30 seconds) and after range changes.

Example:

```json
{"type":"radar_state","v":1,"time":1787770000000,"rangeMi":25,"homeLat":53.8,"homeLon":-1.55,"northUp":true,"aircraft":[{"id":"406b90","callsign":"BAW123","bearing":42.1,"distanceMi":7.8,"altitudeFt":8200,"speedKt":251,"track":215,"onGround":false}]}
```

The glasses animate the sweep locally. They do not receive rendered frames, maps, images, or video.
