# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-11-22

First release. The protocol code is unit tested, but this version has not yet been run against a real watch: expect rough edges and please report what you see.

### Added

- Time sync for the GW-BX5600 from network time (NTP), written so that it reaches the watch on a whole second.
- Background sync: a foreground service listens for the watch and answers its four daily connections and the lower-right button.
- Live reference clock showing the time the watch should display.
- Alarms: five alarms and the hourly time signal.
- Countdown timer start value, with presets.
- Watch settings: 12/24-hour time, date order, weekday language, Standard or Classic digits, auto light, illumination time, button tone, power saving, automatic adjustment and its minute.
- Edits made while the watch is away are stored and written at its next connection.
- Fine adjustment in milliseconds for the time sent to the watch.
