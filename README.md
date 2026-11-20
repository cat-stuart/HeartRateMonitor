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
4. Remove the transmitter or move out of range. The app clears the BPM when it detects a disconnect or five seconds pass without a valid reading. Scan again to reconnect.

In a debug build, tap **Use simulator** to see a repeating BPM sequence without Bluetooth. Tap **Use Bluetooth** to return to sensor setup.

If the monitor does not appear, check that the transmitter is snapped onto the moistened strap, then bring it close to the tablet and scan again. If another app or device is actively using the H10, disconnect it and retry. On older Android versions, BLE scanning can also require Location to be enabled.

This build verifies sensor setup and the live reading path. Saved sensor selection, automatic reconnection, and workout recording are later builds.
