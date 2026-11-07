# Mission P280 — Micromax USB Display

Mission P280 is a lightweight Android receiver for the Micromax P280. It accepts
JPEG frames from a Windows sender over a TCP connection forwarded through ADB
USB, then displays the newest frame full-screen in landscape orientation.

This milestone implements the Android receiver only. It does **not** create a
Windows virtual monitor or extend the Windows desktop. A future Windows
component must provide the virtual display driver, capture frames, encode JPEG,
and send them to this application.

## Compatibility

- Micromax P280 / Android 4.2.2 / API 17
- 800 x 480 display at 160 DPI
- ARM 32-bit devices (`armeabi-v7a` or `armeabi`)
- OpenGL ES 2.0 (the receiver uses a `SurfaceView` and does not require OpenGL)
- No Wi-Fi, Google Play Services, or internet connection is required at runtime

## Architecture

`Windows virtual display (future) -> Windows capture/sender (future) -> ADB USB forwarding -> TCP receiver -> JPEG decoder -> SurfaceView`

The app starts a single background TCP server on port `27183`, accepts one
client at a time, and waits for a new client after disconnects. The renderer
keeps only the latest decoded bitmap to limit memory use.

## Frame protocol

Frames use network byte order (big-endian) and have no delimiter:

```text
[4-byte signed big-endian JPEG length][JPEG payload]
[4-byte signed big-endian JPEG length][JPEG payload]
```

The receiver rejects lengths that are zero, negative, or greater than 4 MiB.
It reads exactly the declared number of bytes and decodes each payload with
Android `BitmapFactory`. Invalid JPEG data is reported and the receiver
continues waiting for the next frame.

## Repository structure

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/voidcrew/micromaxdisplay/MainActivity.java
app/src/main/java/com/voidcrew/micromaxdisplay/UsbReceiver.java
app/src/main/java/com/voidcrew/micromaxdisplay/FrameRenderer.java
.github/workflows/android-build.yml
```

## Cloud build

GitHub Actions uses JDK 11, Gradle 7.5, Android SDK platform 33, and build
tools 33.0.2. Pushes to `main` and manual `workflow_dispatch` runs produce a
debug APK artifact named **Micromax-USB-Display-APK**, retained for 14 days.
The expected output is `app/build/outputs/apk/debug/app-debug.apk`.

No Android Studio or local Android SDK is required for the cloud build.

## Install and run

Download `app-debug.apk` from the workflow artifact, then connect the tablet
with USB debugging enabled:

```text
adb install -r app-debug.apk
adb shell monkey -p com.voidcrew.micromaxdisplay 1
```

Forward the sender's local TCP port to the Android receiver:

```text
adb forward tcp:27183 tcp:27183
```

The future Windows sender should connect to `127.0.0.1:27183` and send the
length-prefixed JPEG protocol described above. To inspect receiver messages:

```text
adb logcat
```

ADB forwarding transports TCP traffic over USB; it does not itself create a
Windows virtual display.

## Status and limitations

The screen reports startup, listening, waiting, connected, receiving,
disconnect, and receiver error states. The initial target is 5–10 FPS at
800 x 480; actual performance depends on JPEG size and the physical tablet.
There is currently no resolution negotiation, frame-rate control, touch input,
Windows sender, virtual display driver, or physical-device performance test.
The receiver accepts one client at a time and uses a 4 MiB maximum JPEG payload.



Window 1 — Connect tablet and install APK
1. Navigate to ADB
cd F:\platform-tools


2. Check whether the tablet is detected
.\adb.exe devices


Expected:
List of devices attached
200804115407093    device


If it says unauthorized, unlock the tablet and accept the USB debugging prompt.

If no device appears, reconnect the USB cable and run .\adb.exe devices again.

3. Restart ADB connection
.\adb.exe kill-server
.\adb.exe start-server
.\adb.exe devices


4. Install the updated APK

Replace the path with your actual downloaded APK location:
.\adb.exe install -r "C:\Users\YOUR_USERNAME\Downloads\app-debug.apk"


Expected:
Success


5. Launch the tablet application
.\adb.exe shell am force-stop com.voidcrew.micromaxdisplay
.\adb.exe shell monkey -p com.voidcrew.micromaxdisplay 1


6. Establish USB port forwarding
.\adb.exe forward tcp:27183 tcp:27183


Verify:
.\adb.exe forward --list


Expected:
200804115407093 tcp:27183 tcp:27183


7. Clear old logs and start monitoring
.\adb.exe logcat -c
.\adb.exe logcat -s FrameRenderer:D AndroidRuntime:E


Keep this window open.

Window 2 — Start Windows desktop streaming
1. Navigate to the project
cd F:\Mission_P_280


2. Verify Pillow is installed
py -m pip show Pillow


If it is missing:
py -m pip install Pillow


3. Start the sender
py windows_sender.py


Expected:
Connecting to Micromax P280...
Connected to tablet!
Streaming desktop. Press Ctrl+C to stop.
Frames: 10 | JPEG size: XX KB


.\adb.exe kill-server
.\adb.exe start-server
.\adb.exe devices
.\adb.exe shell monkey -p com.voidcrew.micromaxdisplay 1
.\adb.exe forward tcp:27183 tcp:27183
.\adb.exe forward --list