# WDGHeadsUp modular firmware overlay

Copy `src/`, `include/`, `test/`, and `platformio.ini` into your existing WDGHeadsUp PlatformIO project. **Keep** your existing `src/pin_config.h` and `lib/Driver_Bus_Library_Based_on_Arduino/` (not included in this archive). Back up or commit the working project first.

- Firmware: PlatformIO Build / Upload (esp32-s3-devkitc-1)
- Native logic tests: `pio test -e native`
- Existing 16MB flash configuration is preserved.
- Touch remains prototype tap cycling; display geometry and sample data are preserved.
- Requires a working host C++ compiler for native tests (Windows: install a suitable MinGW/GCC toolchain if missing).

Hardware upload and display behavior must be verified on the watch; native tests do not test drivers.
