# WDG // HUD licensing and release obligations

## Project license

The maintainer selected **GNU General Public License, version 3 only
(SPDX: `GPL-3.0-only`)** for original WDG // HUD source code, tests,
configuration and project documentation where the project contributors hold
rights to license them. See the complete, unmodified text in [`LICENSE`](../LICENSE).

This selection does **not** change the licenses or copyright ownership of
third-party code, libraries, fonts, embedded assets or tooling. See
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). Do not remove original
license headers or redistribute incompatible proprietary material under
this project license.

## For contributors

- Contribute only code and assets you have the right to license on these terms.
- Preserve attribution and any license notices inherited from third-party code.
- The repository has not yet adopted a contributor agreement or formal DCO
  process; choose an explicit policy before accepting public contributions.

## Before publishing a binary release

1. Build from a recorded Git commit with pinned dependency versions and record
   the resolved Gradle/PlatformIO dependency inventories.
2. Review copyright/license/NOTICE files of every bundled and resolved binary
   dependency, including fonts, graphics, third-party source and embedded assets.
3. Include all required third-party attribution, full license texts and NOTICE
   materials in the appropriate published source/binary distribution.
4. Publish the **corresponding source** and necessary build/install scripts for
   GPL-covered binaries under the GPLv3 distribution requirements.
5. Separately verify Android APK signing identity, versionCode/versionName,
   install/upgrade behavior, and reproducible hashes. Do not publish signing
   secrets or keys.
6. Complete hardware/account-switch, fresh-install and privacy testing from
   [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).

This is a release-engineering guide, not legal advice. A project-wide GPL-3.0-only
selection does not prove that every upstream asset can be redistributed.

## Android application ID during development

Keep existing `applicationId = "com.example.wdghud"` unchanged for current
installations, and avoid changing signing identity during in-place debug app
updates. A separate public identity (and data migration plan, if needed) is a
future release decision. No package rename or signing changes are made here.
