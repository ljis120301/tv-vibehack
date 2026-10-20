# Fire TV

Tested on a Fire TV Stick 4K (AFTKMST12), Fire OS 6.7.1.1 (Android 7.1, API 25).

Fire OS will not let anything replace Amazon's home screen. Each of these was tried on the device and refused or ignored:

| Attempt | Result |
|---|---|
| `pm disable-user com.amazon.tv.launcher` | `Cannot disable a protected package` |
| `pm disable com.amazon.tv.launcher/.ui.HomeActivity_vNext` | same error |
| `cmd package set-home-activity …` | "Success", but Home still resolves to Amazon (priority 950 vs 0) |
| Accessibility service that takes the Home key | Fire OS never binds a third-party accessibility service |
| `am kill com.amazon.tv.launcher` | `Cannot kill protected package` |

So the launcher works around it instead of replacing it:

- `HomeWatchService` follows the system log (ActivityManager `START` lines in the system buffer, `am_resume_activity`
  in the events buffer). When Amazon's home is started (Home, boot) or resumed (Back out of an app that was not opened
  from here), it opens this launcher on top. It runs as a foreground service, so Fire OS neither kills it nor throttles
  its CPU.
- Android holds back that start for five seconds after a Home press. The service therefore covers the screen at once
  with a snapshot of the launcher; an overlay window is not held back. Remote presses made on the snapshot are queued
  and replayed into the launcher when it appears, so navigation is delayed but not lost.
- Apps opened from this launcher return to it with Back.
- The Settings pill opens a menu of Fire OS settings screens: Network, Applications, Controllers & Bluetooth and
  My Fire TV. Display & Sounds, Preferences, Accessibility and Account need `com.amazon.tv.permission.LAUNCHER_SETTINGS`
  (signature|privileged), so "All settings" opens Amazon's own Settings row and the watcher leaves Amazon's home alone
  while you are in it, including screens opened from it. Leaving the row with Back (Amazon logs
  `SettingsNodePresenterDI onStop` while its home is still resumed) or pressing Home returns to the launcher.
- `BootReceiver` brings the launcher up after a reboot. It listens for `LOCKED_BOOT_COMPLETED` as well and asks for
  priority 1000 in these ordered broadcasts. Plain `BOOT_COMPLETED` at default priority reached it about 65 s after
  Amazon's home appeared; with both, the receiver runs before Amazon's home has even started, and the launcher is
  started while the boot animation is still up.

Tested on the stick. Most Home presses were injected with `adb shell input`. One was a kernel-level `KEY_HOME` written
with `sendevent` to the stick's IR receiver (`/dev/input/event0`), which takes the same path as a physical button. The
Bluetooth remote itself was not pressed.

| Case | Result |
|---|---|
| Home on the launcher, or inside Netflix | Cover up 13–163 ms after the press, before Amazon's home draws; live launcher at 5–6 s |
| Hardware `KEY_HOME` (IR receiver) inside a streaming app | Cover up 51 ms after Amazon's home starts; it finished drawing underneath at 780 ms; launcher at 5.3 s |
| Two D-pad presses on the cover | Replayed: focus moved two tiles |
| Back out of an app opened from the launcher | Back on the launcher, no cover needed |
| Settings menu: Applications, then Back | Opens, Back returns to the launcher |
| "All settings", then Home | Amazon's Settings row stays; Home returns to the launcher |
| "All settings" → Network → Back → Back | Network opens, Back returns to Amazon's row, leaving the row returns to the launcher (12 ms) |
| Long-press Home | Amazon's quick panel over the launcher, no bounce |
| Search key | Amazon's text search, Back returns to the launcher |
| Clock screensaver | Runs (tested with a 20 s idle timeout, then put back) |
| `am kill` of the watcher with Netflix in front | Refused; the process survives |
| Reboot | Receiver runs before Amazon's home starts; screenshots every ~2 s show boot logo → launcher, no Amazon home |

What is left: after Home, the launcher snapshot is not interactive for about five seconds (presses are replayed after).
 Amazon's launcher stays in RAM (77–130 MB PSS measured), because
Fire OS will not let it be killed.

## Fire OS 8

Tested on a Fire TV Stick 4K Max (AFTKRT), Fire OS 8.1.8.5 (Android 11, API 30). The Fire OS 6 build did not work:

- **Crash on start.** `startForeground()` threw `SecurityException: ... requires android.permission.FOREGROUND_SERVICE`
  (needed since Android 9). `HomeWatchService` died in `onCreate`, taking `MainActivity` with it, which is the
  spinner-then-gone the first attempt saw. Fixed by declaring `FOREGROUND_SERVICE`; the notification now also has a
  channel (Android 8+ rejects foreground notifications without one), and the service is started with
  `startForegroundService()` on API 26+ so the boot receiver's start is not refused as a background start.
- **Home never seen.** `READ_LOGS` granted over adb still works on Android 11: the app's logcat sees the system and
  events buffers. But Android 10+ logs activity starts as `ActivityTaskManager` (not `ActivityManager`) and the event
  tags are `wm_resume_activity` / `wm_pause_activity` (not `am_*`). The watcher now follows both sets. A Home press
  shows `from uid 0` here, so leaving Amazon's Settings row accepts uid 0 as well as 1000.
- Nothing else needed: `TYPE_APPLICATION_OVERLAY` with the `SYSTEM_ALERT_WINDOW` app-op works, and no
  `foregroundServiceType` is required at targetSdk 29. Amazon's launcher stays protected (not touched).

| Case (Fire OS 8) | Result |
|---|---|
| Install + grants + `am start` | Launcher draws, `HomeWatchService` foreground, no crash |
| `input keyevent HOME` on the launcher | Cover up 26 ms, bounce 66 ms; launcher focused at 7 s |
| Home inside Netflix | Cover up 38 ms, bounce 103 ms; launcher focused |
| Open a tile (Alexa Smart Home), then Back | Back returns to the launcher |
| Reboot | Amazon home shows briefly, then the launcher comes up on its own (~40 s after `adb reboot`); Home works after |

**Home delay and double presses (Fire OS 8).** Android 11 still holds back the launcher's start for five seconds after
Home (`Activity start request from <uid> stopped`), and no permission an adb-sideloaded app can get lifts that hold, so
the snapshot cover stays. Presses made on it are queued and replayed (`replaying N keys from the cover`). A second Home
inside the hold drops the held-back start and restarts the hold; the watcher used to dedupe that second START and the
stick was left on Amazon's home. Now every Home START asks again and pushes the cover's timeout out. The cover also has
a hard 7 s timeout and is removed as soon as any other app resumes.

| Case (Fire OS 8, second round) | Result |
|---|---|
| Home, then Right+Left on the cover, ×3 | Launcher at ~5 s each time, 2 keys replayed |
| Home twice 1 s apart, ×3; Home ×3 back-to-back | Launcher in front each time (failed before this fix) |
| Home inside an app, then Right on the cover | Launcher in front, no cover window left over |

Not tested on Fire OS 8: Settings menu / "All settings" row, hardware remote key, screensaver.

## Install

```bash
cd launcher
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.gathin.tvhome android.permission.READ_LOGS
adb shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.gathin.tvhome/.MainActivity
```

Edit the dock in `launcher/app/src/main/res/values/config.xml` first. The defaults are for one particular stick.

## Remove

```bash
adb uninstall com.gathin.tvhome
```

Nothing of Amazon's is disabled or changed, so this puts the stick back exactly as it was.
