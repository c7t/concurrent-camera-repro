# Concurrent camera repro

A one-activity Camera2 app that streams the rear and front cameras at the
same time. On a Pixel 11 Pro XL the camera provider aborts within seconds to
minutes:

```
F DEBUG : Abort message: 'F0000 ... reentrant_try_lock.h:84] Check failed: scoped_lock is OK (UNAVAILABLE: Concurrent call detected in Tachyon client library. ...
F DEBUG :   #01 pc 00000000004ae998  /apex/com.google.pixel.camera.hal/lib64/libgoog_gasd_unifieddetector.so (BuildId: 605aebb54431eb4f09bfb0bcf9265fd3)
```

The device lists camera IDs {0, 1} in `getConcurrentCameraIds()`, and the app
turns face detection off in both requests.

Tested on build `google/kodiak/kodiak:17/CD1A.261005.003/16262255:user/release-keys`
with camera HAL APEX `com.google.pixel.camera.hal` 2016252900 and
1916262255.

## Build and run

Needs the Android SDK, a JDK and a device on adb.

```
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.c7t.concurrentcamera/.MainActivity
adb logcat -s ConcurrentCamera:* | grep -E 'error|disconnected|fps'
```

The app streams the logical rear camera at its widest zoom (1920x1440) and
the front camera (640x480), both at 30 fps. Each second it logs the frame
rate and worst frame gap of both cameras. When the provider aborts, both
cameras report `error 4` (`ERROR_CAMERA_DEVICE`), and the abort is in
`adb logcat | grep -A20 'Fatal signal.*camera.provider'`.

The app runs over the lock screen and before first unlock, so it can be
started right after `adb reboot`.

## Output

On launch the app logs every camera's characteristics, physical cameras
included. That covers focal lengths, intrinsics, distortion, pose, zoom
range, face detection modes, timestamp source and frame rate ranges. It also
logs the concurrent camera ID sets.

## Options

Pass intent extras to `am start`:

| Extra | Default | Effect |
| --- | --- | --- |
| `--ef zoom` | widest | Rear `CONTROL_ZOOM_RATIO` |
| `--ei fps` | 30 | AE target frame rate range [fps, fps] |
| `--ez faces` | false | `STATISTICS_FACE_DETECT_MODE` SIMPLE instead of OFF |
| `--es rearPhysical` | none | Stream this physical camera of the rear logical camera, unzoomed |
| `--ei rearWidth` | 1920 | Largest 4:3 rear stream no wider than this |
| `--ei frontWidth` | 640 | Largest 4:3 front stream no wider than this |
| `--ez lean` | false | Turn off noise reduction, edge enhancement and stabilization |
| `--es mode` | soak | `switch` streams one camera at a time |
| `--ei period` | 3000 | In switch mode, ms of front streaming between switches |
| `--ez reopen` | false | In switch mode, close and reopen the cameras on each switch |
| `--ez abort` | false | In switch mode, stop with `abortCaptures()` instead of `stopRepeating()` |

Switch mode keeps both cameras open and configured. It streams the front
camera, then the rear until auto-exposure converges, then the front again.
Each switch logs the time to the first rear frame and the gap in front
frames. It has not aborted the provider.
