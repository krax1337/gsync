# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub: **Security → Report a vulnerability** on this repository. Do not open a public issue.

Include the affected version, steps to reproduce and the impact. You will get an acknowledgement within a few days.

## Supported versions

Only the latest release receives fixes.

## Verifying a release

Every APK on the Releases page is built by the `Release` workflow from the tagged commit and signed with one key. Its certificate has this SHA-256 fingerprint:

```
2c:b6:6b:fd:8b:ca:fb:a9:f6:48:34:53:18:8b:0a:37:0e:5b:e8:29:45:13:47:1d:59:8f:c4:fc:8b:cf:0a:c6
```

Check a download before installing it:

```sh
sha256sum --check SHA256SUMS                    # the file is the one the workflow published
apksigner verify --print-certs gsync-0.1.0.apk  # "certificate SHA-256 digest" must match the fingerprint above
```

Android itself refuses an update that is not signed with the key of the installed app.

## Scope notes

- The app talks to one paired watch over Bluetooth and to public NTP servers (`time.cloudflare.com`, `time.google.com`, `pool.ntp.org`). It has no account, no backend and no analytics.
- It holds no secrets. What it stores on the phone is the watch's Bluetooth address and the last alarms, timer and settings read from the watch, excluded from device backups.
- NTP is not authenticated. Someone who controls your network can make the app set the watch to a wrong time; they cannot do anything else through it.
- The signing key is kept in a GitHub Actions environment that only the signing job of the `Release` workflow can read. That job runs no code from the repository and no build tooling.
