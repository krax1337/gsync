# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-11-22

### Added

- Experimental support for the Casio watches that keep their clocks in registers: the GW-B5600 and GMW-B5000 squares, the GA-B2100 line, G-STEEL models with a display, GBD and GBA step trackers and a few more (the README lists them). It is written from what is known about the protocol and has not been tried on any of these watches. The app marks such a watch as experimental, shows only what the model has, and leaves its home city and time zone the way they are set on the watch.
- Watches the app cannot serve are shown as "Not supported" when pairing instead of failing later.
- Several watches in one app. The title of the Watch tab opens the list of paired watches and "Add a watch". Each watch keeps its own alarms, timer, settings, sync history and fine adjustment, and the app listens for all of them in the background.
- The cities set on the watch: the Watch tab shows the home city and the world time cities read at the last sync, and says whether the home city fits the phone's time zone. How the GW-BX5600 stores these names is inferred, not documented, so the card stays hidden if they cannot be read.
- Diagnostics page in Settings: the raw clock tables the watch sent at the last sync, with a copy button, for bug reports.

### Changed

- New app icon: a square digital watch showing `:00`, the exact second.
- A watch paired with an earlier version is carried over with everything known about it.
- The README is in English only and shows the app in one strip of three screens.

## [0.1.1] - 2026-11-22

Time sync is now confirmed on a real GW-BX5600. Alarms, timer, settings, background sync and the new phone finder are still waiting for their first test on hardware.

### Added

- Phone finder: holding the lower-right button on the watch for five seconds rings the phone at alarm volume. Stop it from the notification, in the app, or with any watch button during the first 30 seconds. Can be switched off in Settings.
- Watch guide in the Settings tab: buttons, syncing, home city, world time, swapping and travelling, with the city to choose on the watch for the phone's time zone and the full list of built-in cities.

### Changed

- The fifth alarm is labelled as what it is on the watch: the snooze alarm.
- The Alarms tab notes that the watch does not sync while its timer is counting down.

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
