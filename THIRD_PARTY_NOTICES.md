# Third-party software notices — WDG // HUD

**Status:** G5A50.2 first-pass source dependency inventory. Not yet a verified complete
bill of materials for a public APK or watch firmware binary. Original WDG // HUD
code is licensed under GPL-3.0-only; **upstream code and assets keep their own
copyrights, licenses, and required notices**. The top-level GPL license does not
replace those upstream obligations.

| Component | Where used | Upstream license evidence | Distribution note |
| --- | --- | --- | --- |
| **Arduino DriveBus** (Xk_w) | Vendored under `WDGHeadsUp/lib/Driver_Bus_Library_Based_on_Arduino/` | GPL 3.0; see its unmodified `LICENSE` and source headers, https://github.com/Xk-w/Arduino_DriveBus | Preserve bundled license, author notices and corresponding source; verify precise version and any local changes |
| **Arduino_GFX / GFX Library for Arduino** (moononournation; Adafruit-derived components) | PlatformIO `lib_deps` of the watch | Upstream `license.txt`, BSD-style with Adafruit copyright: https://github.com/moononournation/Arduino_GFX/blob/master/license.txt | Retain applicable BSD copyright/disclaimer in source and/or binary distribution documentation; verify notices for fonts/assets and resolved version |
| **NimBLE-Arduino** (h2zero and upstream contributors) | PlatformIO `h2zero/NimBLE-Arduino@^2.3.7` | Apache License 2.0, upstream `LICENSE` and `NOTICE`: https://github.com/h2zero/NimBLE-Arduino | Include applicable Apache-2.0 license and notices in distribution; record resolved version before release |
| **Arduino ESP32 platform / ESP-IDF and dependencies** | Watch compiler/runtime via `platform = espressif32` | Various component-specific terms; not exhaustively reviewed here | Resolve exact PlatformIO platform/framework packages and review their bundled notices; GPL-3.0-only project licensing does not override them |
| **AndroidX Activity, Compose UI/Material3, Core, Lifecycle and test libraries** | `WDGHUD-Android/gradle/libs.versions.toml` | Primarily Apache-2.0 in upstream AndroidX source: https://github.com/androidx/androidx | Audit final resolved Gradle dependency graph, including transitive modules, and reproduce applicable notices |
| **Kotlin, Gradle, Android Gradle Plugin and other build tooling** | Android build tools | Upstream licenses vary by component/version | Record build tool versions and compliance for any redistributed tool binaries; do not treat tools as automatically incorporated into the app |

## Scope and compatibility

GNU GPL version 3 is generally compatible with Apache License 2.0 and permissive
BSD-style software licensing, subject to keeping applicable copyright, license
and NOTICE materials. See https://www.apache.org/licenses/GPL-compatibility.html .

This document is an engineering notice inventory, not a legal clearance opinion
or an assertion that the listed dependencies constitute every package in the
final binaries. A final release must inventory **actual resolved versions**, review
all transitive dependencies and bundled data/assets, preserve required notices,
and provide GPLv3 corresponding-source information for distributed covered binaries.

**Do not delete or replace third-party license files in vendor directories.**
