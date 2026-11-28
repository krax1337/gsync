# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [0.3.0] - 2026-11-28

### Added

- Today's steps on the Watch tab for the ABL-100WE, F-B100W and BSA-B100. They are read at every connection, after the time is set. G-Sync starts the transfer of the watch's activity record and never ends it, which by what GShockAPI says leaves the step history on the watch for the official app to collect. Experimental: ported from GShockAPI 2.0.0 and not tried on a watch. The GBD and GBA sports models show no steps, because how they report them is not known. Nothing changes on the GW-BX5600.
- The clock on the Watch tab is drawn like the display of the watch. The GW-BX5600 gets its own face: weekday and boxed date over the time, in the Classic or the Standard digits the watch is set to, without the faint unlit segments its display does not have. The GBD and GBX sports models get theirs: the date above a rule, the time across the width, weekday and seconds below. The other models keep the seven-segment face.
- Clock face in Settings: "Automatic" follows the model and the watch's digit setting, or pick any of the faces yourself. Kept for each watch.

### Changed

- A new version is announced when you open the app, with what is new and a button to update. Until now it was only shown in Settings. "Later" puts it off until the app is opened again.

## [0.2.4] - 2026-11-28

### Fixed

- Experimental watches were shown as "Not supported" when pairing and could not be paired. Watches pad the name they advertise with zero bytes, and the app took those for part of the name. Reported for the GBX-100, GA-B2100 and GMW-B5000.
- The squares that advertise their name with a # at the end (GMW-B5000#, GW-B5600#, MRG-B5000#) are now recognised.
- Switching automatic adjustment on or off no longer clears the rest of the byte it lives in, where some models keep other data. Nothing changes on the GW-BX5600. Ported from GShockAPI 2.0.0.
- A sync no longer fails with "Write refused by Bluetooth stack (201)" when Android is still busy with another write to the watch, as it can be while a second watch app on the phone talks to it. G-Sync now waits its turn for up to three seconds. Not yet confirmed by the person who reported it.

## [0.2.3] - 2026-11-27

### Added

- Updates from inside the app. When you open G-Sync it looks at GitHub for a newer release, and Settings → App offers it. On your tap G-Sync downloads it and checks that it is signed with the same key as the installed app, and Android asks you to confirm. Nothing is downloaded unless you ask.
- Diagnostics now start with a line on how the last sync was timed: where the time came from, how long the link was idle before the time was written, and how long the watch took to acknowledge it. For reports of a watch that ends up early or late.

## [0.2.2] - 2026-11-27

### Added

- Licences page in Settings: the notices of G-Sync and of the open-source work inside it. They now travel with the APK.

### Fixed

- GShockAPI is under the MIT licence, not Apache-2.0 as the README and NOTICE said. Its licence text is now included.

## [0.2.1] - 2026-11-22

The cities shown from the watch are now confirmed on a real GW-BX5600.

### Fixed

- The world time row listed each city twice, once by name and once by its three-letter code, with the home city's code in between ("(utc), Tokyo, Khi, UTC, Tyo"). It now lists the two world time cities.

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
