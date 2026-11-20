# Home Gym Heart Rate Monitor

## Build 1 — Live BPM

Open the project in Android Studio and run the app on the tablet over USB debugging. To build the debug APK and run local unit tests from PowerShell, use the Gradle wrapper:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

1. Moisten the Polar H10 electrodes and wear the strap.
2. Tap **Scan for monitors** and grant the requested Android permission. Enable Bluetooth if Android prompts you.
3. Select the Polar H10. Wait for the first reading and confirm the BPM changes as you move.
4. Remove the transmitter or move out of range. The app clears the BPM when it detects a disconnect or five seconds pass without a valid reading. The app retries the saved monitor automatically.

In a debug build, tap **Use simulator** to see a repeating BPM sequence without Bluetooth. Tap **Use Bluetooth** to return to sensor setup.

If the monitor does not appear, check that the transmitter is snapped onto the moistened strap, then bring it close to the tablet and scan again. If another app or device is actively using the H10, disconnect it and retry. On older Android versions, BLE scanning can also require Location to be enabled.

## Build 2 — Reconnection

After the first live reading, the app remembers your monitor. Close and reopen the app with the strap on to verify that readings return without scanning. Turn Bluetooth off and back on, then move out of range and back. The BPM should clear when readings stop and return when the connection recovers. **Reconnect** retries immediately. **Scan for monitors** lets you choose a different strap.

Reconnection runs while the app is visible. Background workout tracking and recording are later builds.

## Build 3 — Dashboard

Open **Monitor** to manage the Polar H10 or turn on the debug simulator. Open **Settings** to save your maximum heart rate and preview the five zone boundaries. Until you set it, the dashboard labels 185 BPM as provisional. The timer uses the max HR in effect at workout start for that session.

Start a workout, pause it for a few seconds, resume, then finish. Check that elapsed time stays fixed while paused and after confirmation. Rotate the tablet during the workout to check that the timer continues. The trend covers the last five minutes and leaves gaps when readings stop.

This build keeps the workout timer in memory. Saved workouts, zone durations, summaries, and history are planned for Build 4.

## Build 4 — Saved workouts

Start a workout, then choose **Monitor → Use simulator** for a strap-free test. While recording, choose **Monitor → Simulate 8-second signal loss**. The BPM clears during the gap and returns automatically. Finish the workout to check **No Data**, time in zones, time-weighted average BPM, peak BPM, and the chart.

For a recovery test, start another workout, let it run for at least ten seconds, then force-stop the app and reopen it. The app restores the latest checkpoint with **Resume** and **Finish**. Time between the checkpoint and relaunch is excluded. The app checkpoints every five seconds while active and also at start, pause, resume, and when the dashboard goes into the background.

Completed workouts appear in **History**. Use **View workout** to reopen the summary or **Delete** to remove the workout and samples. Workouts are stored as atomic JSON files in the app's private internal storage. Background collection from the physical strap is planned for Build 5.
