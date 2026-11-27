# Contributing

## Reports from watch owners

The most useful contribution is a report from a real watch, above all from a model the README lists as experimental. Open a [watch report](https://github.com/krax1337/gsync/issues/new?template=watch-report.yml) and paste the text from Settings → Diagnostics. That text includes the coordinates of the home city set on the watch (a city, not your position); remove them if you prefer.

## Bugs

Open a [bug report](https://github.com/krax1337/gsync/issues/new?template=bug.yml). Vulnerabilities go through [SECURITY.md](SECURITY.md), not through a public issue.

## Code

- **Build:** `mise install`, then `mise run sdk` once. `mise run test` and `mise run apk` must pass, as they must in CI.
- **Protocol changes:** add a unit test with the frames involved, and say in the pull request whether the change was tried on a watch, and on which model.
- **Size:** one change per pull request. For anything larger than a fix, open an issue first.
- **Code from other projects:** name the project and its licence in the pull request. Code under the GPL or AGPL (Gadgetbridge, for one) cannot be copied or translated into G-Sync; what it documents about the protocol can be implemented anew.

Contributions are accepted under the [Apache License 2.0](LICENSE), as its section 5 says.
