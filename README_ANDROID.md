# LiquidBounce for Android (ZalithLauncher / PojavLauncher)

This document describes how to build and use LiquidBounce on Android devices
via ZalithLauncher, PojavLauncher, or similar launchers.

## Platform Compatibility

LiquidBounce v0.39.0 has been adapted to run on Android-based Minecraft launchers.
The following features are automatically disabled on Android:

| Feature              | Status        | Reason                                    |
|----------------------|---------------|-------------------------------------------|
| JCEF / MCEF Browser  | ❌ Disabled   | No Chromium Embedded Framework for ARM    |
| Discord RPC          | ❌ Disabled   | No Discord native libraries for Android   |
| Deep Learning (DJL)  | ❌ Disabled   | PyTorch native libs unavailable for ARM   |
| WayGL Acceleration   | ❌ Disabled   | Wayland-specific, not applicable          |
| LWJGL EGL            | ❌ Disabled   | Linux-specific EGL bindings               |
| Native File Dialogs  | ❌ Disabled   | TinyFileDialogs not available on Android  |
| Standard Modules     | ✅ Working    | All hack modules work normally            |
| Script API           | ✅ Working    | JavaScript scripting fully functional     |
| ViaFabricPlus        | ✅ Working    | Cross-version compatibility              |

## Building for Android

### Prerequisites
- JDK 21
- Gradle (wrapper included)

### Method 1: Using Android build profile

```bash
# Build with Android-specific optimizations
./gradlew build -Pbuild.target=android

# The output JAR will be in: build/libs/liquidbounce-android-0.37.0.jar
```

### Method 2: Using the provided properties file

```bash
# Copy Android properties
cp gradle-android.properties gradle.properties

# Build normally
./gradlew build
```

### Method 3: Standard build (also works with runtime detection)

```bash
# Standard desktop build also works on Android -
# incompatible features are disabled at runtime
./gradlew build
```

## Installation on Android

### ZalithLauncher
1. Build the mod JAR (or use pre-built release)
2. Open ZalithLauncher
3. Go to your Minecraft instance settings
4. Navigate to "Mods" section
5. Add the LiquidBounce JAR
6. Launch Minecraft

### PojavLauncher
1. Build or download the mod JAR
2. Place it in `.minecraft/mods/` directory
3. Launch Minecraft

###FoldCraftLauncher
1.Build or downloaf the mod JAR
2.Download 26.2Minecraft(FabricLoader)
3.Place it in ".minecraft/mods/"directory
4.Launch Minecraft

## Performance Notes

- On lower-end Android devices, consider disabling resource-intensive modules
- The ClickGUI theme renders via browser (disabled on Android) — a simplified
  Android-compatible UI is used as fallback
- Reduce render distance for better FPS on mobile devices

## Troubleshooting

### Mod crashes on startup
- Ensure you're using the Fabric loader (not Forge)
- Make sure `fabric-language-kotlin` is installed
- Check that incompatible JAR-in-JAR dependencies are excluded

### Black screen / no GUI
- The browser-based GUI (ClickGUI) is disabled on Android by default
- Use command-based configuration: type `.help` in chat for available commands

### Font issues
- LiquidBounce will try to use Android system fonts (Droid Sans / Roboto)
- If CJK characters don't display, install a CJK font on your device

### Keybinds not working with touch
- ZalithLauncher provides virtual keyboard and mouse controls
- Configure touch controls in the launcher settings
- Use external keyboard/mouse for best experience

## Compatibility

- Minecraft 26.2 (Java Edition)
- Fabric Loader ≥ 0.19.3
- Fabric Language Kotlin ≥ 1.13.9
- Android 7.0+
- ZalithLauncher 2.x / PojavLauncher

## License

GNU General Public License v3.0 — same as the main LiquidBounce project.
