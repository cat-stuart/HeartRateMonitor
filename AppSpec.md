# **Home Gym — Custom Heart Rate Dashboard**

## **Android Application Engineering Specification**

**Version:** 1.0  
 **Priority:** Production-quality MVP within 10 days  
 **Target device:** Lenovo Tab One 8.7-inch Android tablet, or equivalent  
 **Heart-rate sensor:** Polar H10 chest strap  
 **Deployment:** Locally installed Android APK

---

## **1\. Project Overview**

Build a polished, dedicated fitness dashboard application for an Android tablet that connects to a Polar H10 heart-rate monitor via Bluetooth Low Energy (BLE).

The app should recreate the most useful aspects of an Orangetheory-style heart-rate display, with large real-time heart-rate readings, color-coded training zones, workout timing, heart-rate history, and workout summaries.

The tablet will normally live on a magnetic wall mount in a home gym but will frequently be carried into a separate treadmill room.

**The most important product requirement is simplicity.** The user should rarely need to interact with settings, manage Bluetooth connections, or restart the application.

The application must operate without internet access, a user account, or external infrastructure.

### **Primary goals**

1. Connect reliably to the existing Polar H10.  
2. Display live heart rate with minimal latency.  
3. Display intuitive, color-coded heart-rate zones.  
4. Provide simple workout start, pause, resume, and finish controls.  
5. Track workout duration and time spent in each zone.  
6. Save a useful summary of completed workouts.  
7. Automatically reconnect to the previously selected sensor.  
8. Provide a beautiful, fitness-focused, tablet-optimized interface.  
9. Work in both portrait and landscape orientations.  
10. Remain easy to use for someone who is not interested in technology or configuring devices.

### **Out of scope for version 1**

* Cloud accounts or cloud synchronization  
* Social features or leaderboards  
* Integration with Strava, Garmin, Apple Health, or Google Fit  
* Streaming ECG waveforms  
* AI coaching  
* Automatic workout detection  
* Calories burned or other speculative metrics  
* Multiple simultaneous heart-rate sensors  
* Heart-rate-based interval automation  
* TV casting  
* Dedicated hardware interval clock functionality

The separate physical interval clock will handle dedicated interval training.

---

## **2\. Technical Architecture**

### **Recommended technology stack**

| Layer | Technology |
| ----- | ----- |
| Language | Kotlin |
| UI | Jetpack Compose \+ Material 3 |
| Application architecture | MVVM |
| Reactive state | Kotlin Coroutines and StateFlow |
| Bluetooth | Native Android BLE APIs |
| Workout storage | Versioned JSON files in private internal storage |
| Preferences | Android SharedPreferences |
| Navigation | Navigation Compose |
| Dependency injection | Hilt, if convenient |
| Charts | Lightweight Compose Canvas drawing |
| Build system | Gradle Kotlin DSL |
| Target | Current stable Android SDK supported by the development environment |

Use recent stable, compatible dependencies. Avoid introducing unnecessary frameworks.

### **Design principles**

* Native Android implementation rather than a web-based dashboard.  
* No backend or network dependency.  
* Sensor connectivity must be separated from UI rendering.  
* All UI state must derive from centralized, observable application state.  
* BLE operations must be asynchronous and lifecycle-aware.  
* Bluetooth failure must not crash the application or discard workout data.  
* The architecture should allow a simulated heart-rate sensor for development and automated testing.

### **Suggested project structure**

app/  
  src/main/  
    java/com/homegym/dashboard/  
      MainActivity.kt  
      HomeGymApplication.kt

      bluetooth/  
        HeartRateSensor.kt  
        PolarH10Manager.kt  
        HeartRateParser.kt  
        ConnectionState.kt

      workout/  
        WorkoutManager.kt  
        WorkoutState.kt  
        HeartRateZoneCalculator.kt  
        WorkoutSummaryCalculator.kt
        WorkoutFileStore.kt

      ui/  
        dashboard/  
          DashboardScreen.kt  
          DashboardViewModel.kt  
          HeartRateCard.kt  
          ZoneIndicator.kt  
          WorkoutControls.kt  
          HeartRateChart.kt

        history/  
          HistoryScreen.kt  
          WorkoutDetailScreen.kt

        settings/  
          SettingsScreen.kt  
          SensorSetupScreen.kt

        theme/  
          Color.kt  
          Theme.kt  
          Typography.kt

      service/  
        WorkoutTrackingService.kt

Exact file organization can be adjusted for maintainability.

---

## **3\. Bluetooth Integration**

### **3.1 Hardware**

The application must communicate with a Polar H10 heart-rate monitor using Bluetooth Low Energy.

Use the standard Bluetooth Heart Rate Service rather than the proprietary Polar SDK for version 1\.

Relevant UUIDs:

Heart Rate Service  
0000180D-0000-1000-8000-00805F9B34FB

Heart Rate Measurement  
00002A37-0000-1000-8000-00805F9B34FB

Client Characteristic Configuration  
00002902-0000-1000-8000-00805F9B34FB

### **3.2 Initial sensor setup**

On first launch:

1. Display a welcome screen explaining that the app needs a heart-rate monitor.  
2. Request the required Bluetooth permissions.  
3. Check whether Bluetooth is enabled.  
4. Ask the user to put on the H10 chest strap and moisten the electrodes.  
5. Scan for nearby BLE devices advertising the Heart Rate Service.  
6. Display discovered devices with name and signal strength where available.  
7. Allow the user to select the Polar H10.  
8. Connect and discover GATT services.  
9. Subscribe to Heart Rate Measurement notifications.  
10. Verify that live BPM data is received.  
11. Save the selected sensor’s identifier for future reconnection.  
12. Open the main dashboard.

The first-time workflow should be simple and guided.

### **3.3 Heart-rate data parsing**

The Heart Rate Measurement characteristic uses the standard Bluetooth Heart Rate Profile.

Implement a parser supporting:

* 8-bit unsigned heart-rate values.  
* 16-bit unsigned heart-rate values.  
* Optional RR-interval fields, which may be ignored in version 1\.  
* Optional energy-expended fields, which may be ignored.

Read bit 0 of the flags byte to determine whether BPM is represented using 8 or 16 bits.

Parse with the correct little-endian byte order.

Validate packet lengths before reading.

Do not interpret RR intervals or energy-expended values as BPM.

Use received BPM values directly for the numeric display. Optional smoothing may be used for graph rendering, but should never silently alter the displayed sensor reading.

### **3.4 Connection states**

Implement an explicit connection state machine:

BLUETOOTH\_DISABLED  
PERMISSION\_REQUIRED  
NOT\_CONFIGURED  
SCANNING  
CONNECTING  
CONNECTED  
RECONNECTING  
DISCONNECTED  
ERROR

Every state must have an appropriate UI representation.

Examples:

* CONNECTED: green indicator, live BPM.  
* CONNECTING: subtle progress indicator.  
* RECONNECTING: retain last known BPM visually but mark it stale.  
* DISCONNECTED: display `--` for current BPM after the stale threshold.  
* BLUETOOTH\_DISABLED: explain how to enable Bluetooth.  
* ERROR: display a simple Retry action.

Never display a stale heart-rate value as though it is live.

### **3.5 Automatic reconnection**

This is a critical feature.

After the user initially selects the H10, the app should remember it and attempt to connect automatically when opened.

Expected behavior:

1. Launch app.  
2. Load previously configured sensor.  
3. Attempt reconnection when permitted by Android.  
4. Discover services, enable notifications, and resume readings.  
5. If unavailable, display a friendly waiting state.  
6. Retry with bounded exponential backoff.  
7. Cancel stale connection attempts.  
8. Reset backoff when a successful connection is established.

Suggested backoff intervals:

2 seconds  
5 seconds  
10 seconds  
20 seconds  
30 seconds maximum

Use timeouts and avoid overlapping scans or concurrent GATT connections.

Handle cases where the H10 is unavailable, turned off, or already connected to other devices.

Do not require a new pairing process after ordinary disconnections.

Provide a manual Reconnect button for troubleshooting.

### **3.6 Bluetooth permissions**

Implement API-level appropriate permission handling.

On Android 12 and newer, request the relevant Nearby Devices permissions:

BLUETOOTH\_SCAN  
BLUETOOTH\_CONNECT

Use `neverForLocation` for BLE scanning only if the implementation genuinely does not derive physical location and its filtering implications are acceptable.

For older supported Android versions, handle legacy Bluetooth and location permission requirements correctly.

Do not request unnecessary permissions such as camera, microphone, contacts, or phone state.

### **3.7 Lifecycle behavior**

* BLE connection state should survive Compose screen navigation.  
* Configuration changes must not create duplicate connections.  
* Closing a settings screen must not interrupt an ongoing workout.  
* If the tablet temporarily sleeps, recover the connection when possible.  
* During an active workout, use a foreground service with an ongoing notification when required to maintain tracking outside the foreground.  
* Implement the appropriate `connectedDevice` foreground service type and permissions for modern Android.  
* Respect Android restrictions on starting background services.  
* Release connections and foreground resources when tracking is no longer needed.  
* Do not attempt to bypass Android power-management or background execution restrictions.

Provide clear indication if the app can no longer record heart-rate data.

---

## **4\. Main Dashboard**

### **Visual style**

Use a dark, modern, minimal interface similar to a premium fitness display.

The dashboard should feel like a dedicated piece of gym equipment, not a generic Android application.

Design objectives:

* Dark navy or near-black background.  
* Bright, high-contrast numerical readings.  
* Large typography legible from several feet away.  
* Color used primarily to communicate heart-rate intensity.  
* Subtle borders and card treatments.  
* Clean layout with generous spacing.  
* Minimal navigation clutter.  
* Responsive to different tablet sizes and orientations.

### **Main display elements**

**A. Live heart rate**

The largest element on screen.

Example:

        154  
          BPM

The BPM value should be prominent and update as new readings arrive.

Display a small heart icon and connection indicator.

**B. Current training zone**

A large color-coded panel showing:

ZONE 4  
ORANGE  
83% OF MAX HR

The current zone should update automatically as the BPM changes.

**C. Workout timer**

Display:

WORKOUT TIME

24:38

Use a monotonic elapsed-time source for in-progress timing, not wall-clock differences alone.

**D. Heart-rate zone bar**

Display five colored segments representing the training zones.

Highlight the active zone.

**E. Live heart-rate chart**

A rolling graph showing recent BPM history.

Initial scope:

* Last 5 minutes by default.  
* Update on incoming readings.  
* No excessive animation.  
* Properly handle missing readings.  
* Draw visible gaps when the sensor is disconnected.  
* Avoid rendering fabricated zero BPM samples.

**F. Workout controls**

Display a prominent button:

START WORKOUT

During a workout:

PAUSE    FINISH

When paused:

RESUME   FINISH

Finishing should lead to a workout summary.

### **Idle behavior**

When no workout is active:

* Continue displaying live HR when connected.  
* Show the current zone.  
* Show a clear Start Workout button.  
* Do not save a workout automatically.  
* Do not increment workout duration.  
* Do not require a connected sensor merely to navigate the app.

The app should still be useful as a passive heart-rate display.

---

## **5\. Heart-Rate Zones**

Use five training zones.

Default zone thresholds:

| Zone | % of max HR | Color |
| ----- | ----- | ----- |
| Zone 1 | Below 60% | Gray |
| Zone 2 | 60–69% | Blue |
| Zone 3 | 70–79% | Green |
| Zone 4 | 80–89% | Orange |
| Zone 5 | 90% and above | Red |

These thresholds are initial product defaults, inspired by the familiar five-color fitness display. They should not be presented as official Orangetheory calculations.

### **Zone calculation**

percentage \= (currentBpm / maxHeartRate) \* 100

Calculate the zone from the unrounded percentage rather than rounding before comparison.

Max heart rate must be configurable in Settings.

Do not hardcode a physiological estimate based on age. If the user has not provided a maximum heart rate, show a clearly labeled provisional default and prompt for configuration.

Suggested initial default:

maxHeartRate \= 185

This is only a placeholder, not a personalized medical or fitness assessment.

Users must be able to edit max HR at any time.

Allow future support for custom zone thresholds without requiring a major refactor.

### **Zone duration tracking**

During active workout recording, accumulate time spent in each zone.

Requirements:

* Zone timing counts only periods with valid, fresh heart-rate data.  
* Do not count paused time.  
* Do not allocate disconnected periods to the last known zone.  
* Avoid double-counting when multiple samples arrive within a short interval.  
* Use monotonic timestamps for time accounting.  
* Persist enough data to reconstruct accurate zone durations.

Define stale data as no valid BPM update for more than 5 seconds; make this configurable in code.

For elapsed time between consecutive samples, attribute the interval to the previously observed zone only when the readings are fresh and the interval is within the freshness threshold.

Use a separate **No Data** category for uncovered workout time.

### **Workout zone summary**

After a workout, display:

TIME IN ZONES

GRAY       02:15  
BLUE       04:30  
GREEN      12:45  
ORANGE     08:20  
RED        01:10  
NO DATA    00:40

Show total tracked time and optionally a stacked distribution bar.

Do not call this a clinical assessment or imply that time in higher zones is inherently better.

---

## **6\. Workout Session Management**

Implement the following workout states:

IDLE  
ACTIVE  
PAUSED  
FINISHED

### **Starting a workout**

When Start Workout is pressed:

* Create a new workout session.  
* Record start timestamp.  
* Begin active elapsed-time tracking.  
* Begin capturing incoming heart-rate samples.  
* Begin zone-duration accumulation.  
* Keep the screen awake while the workout is actively displayed.  
* Start the tracking foreground service if appropriate.

If the sensor is disconnected, allow starting a session but make the missing live data obvious.

### **Pausing**

When Pause is pressed:

* Stop the active timer.  
* Stop accumulating zone durations.  
* Stop persisting workout HR samples.  
* Keep the existing workout session in memory and durable storage.  
* Maintain the sensor connection if possible.

### **Resuming**

When Resume is pressed:

* Continue the current session.  
* Preserve previously recorded duration.  
* Resume HR sample capture and zone accumulation.

### **Finishing**

When Finish is pressed:

* Present a confirmation to prevent accidental termination.  
* Finalize the workout.  
* Compute summary statistics.  
* Persist session data.  
* Display the workout summary screen.

### **Workouts interrupted by app termination**

Persist active session checkpoints.

If the app is terminated or the tablet restarts:

* Do not silently discard the workout.  
* Restore the known session state on next launch.  
* Offer Resume or Finish.  
* Do not automatically count an unknown interruption period as active workout time.  
* Mark any interval lacking sensor readings as No Data.

Correctness and avoiding fabricated statistics are more important than pretending an interrupted session continued perfectly.

---

## **7\. Workout Summary**

After finishing a workout, show a dedicated summary screen.

### **Required statistics**

* Workout date  
* Start time  
* Active workout duration  
* Average HR  
* Maximum recorded HR  
* Time spent in each zone  
* Percentage of tracked time in each zone  
* Total time without valid sensor data  
* Heart-rate graph covering the workout

### **Optional version 1 statistics**

* Minimum recorded HR  
* Total samples received  
* Percentage of active time with valid HR coverage

If no heart-rate samples were collected, display unavailable statistics as `--` rather than zero.

### **Average HR**

Calculate a time-weighted average over intervals with valid HR coverage, rather than simply averaging arbitrarily sampled BPM values.

Do not interpolate through extended disconnections.

### **Summary navigation**

Provide:

DONE

Returns to dashboard.

The completed session must be accessible from Workout History.

---

## **8\. Workout History**

Create a simple history page accessible from the main dashboard.

Display completed workouts in reverse chronological order.

Each row should show:

NOV 19  
Strength Training  
32:15  
Avg HR 142 BPM

The workout type can default to `Workout`.

User-defined workout names are optional for version 1\.

Tapping a workout opens its saved summary.

Support deletion with confirmation.

A deleted workout must remove its associated sample data.

No cloud syncing is required.

---

## **9\. Local Data Model**

Store one versioned JSON file per workout in private internal storage. Replace it atomically when writing a checkpoint or completing a session. A completed file contains the workout start and finish times, active duration, maximum heart rate setting, and samples. An unfinished file serves as the current checkpoint. Save it at start, pause, resume, when leaving the dashboard, and every five seconds during active recording. Restore unfinished sessions paused and exclude time after their latest checkpoint.

Each sample includes its active elapsed timestamp, BPM, and a flag marking the beginning of a continuous segment. Use these values to reconstruct the chart, zone durations, No Data, time-weighted average, and peak BPM. Calculate statistics against the max HR captured at workout start. Deleting a workout removes its file and associated samples.

### **User preferences**

Use Android SharedPreferences for selected sensor identifier and name, configured maximum heart rate, whether the setup introduction was shown, and whether to keep the screen awake during a workout. Follow the tablet orientation and adapt the dashboard to the available width.

### **Privacy**

All data must remain local to the tablet.

No telemetry, analytics SDKs, third-party tracking, advertising, or remote uploads.

Do not log actual heart-rate readings or device identifiers in production builds.

---

## **10\. Settings Screen**

The settings screen should be small and straightforward.

Required options:

**Heart-rate monitor**

* Show connected sensor.  
* Connection status.  
* Reconnect.  
* Change sensor.  
* Forget sensor.

**Training zones**

* Edit maximum HR.  
* Preview calculated zone boundaries.

**Display**

* Keep screen awake during workouts.  
* Theme defaults to dark.  
* Support portrait and landscape.

**Diagnostics**

* Show Bluetooth permission state.  
* Show last successful reading time.  
* Show app version.  
* Show simple troubleshooting instructions.

**Data**

* View workout history.  
* Delete workout history with confirmation.

Do not expose low-level Bluetooth configuration unnecessarily.

---

## **11\. User Experience and Visual Behavior**

### **First launch**

The application should feel like a dedicated consumer fitness device.

Suggested flow:

WELCOME  
   ↓  
CONNECT HEART RATE MONITOR  
   ↓  
SET MAX HEART RATE  
   ↓  
DASHBOARD

Once onboarding is complete, it should never appear again unless the user intentionally resets the configuration.

### **Subsequent launches**

Launch directly to the dashboard.

Show:

Connecting to Polar H10...

Then transition automatically to live BPM once connected.

Do not show a splash screen for longer than necessary.

### **Design considerations**

* Use a landscape-first layout when screen width allows.  
* The BPM display should remain prominent in portrait.  
* Buttons must have generous touch targets.  
* Avoid tiny text.  
* Use consistent margins.  
* Support system font scaling without clipping.  
* Provide text zone labels in addition to colors for accessibility.  
* Keep animations subtle.  
* The main screen should not require vertical scrolling on the target tablet at normal display settings.

### **Screen behavior**

During an active workout:

* Keep the display awake while the workout UI is visible.  
* Allow the user to lock the tablet if desired.  
* Continue tracking under supported Android background execution rules.  
* Show a persistent notification when background tracking is active.

When idle:

* Allow normal Android sleep and battery-saving behavior.  
* The user should be able to wake the tablet and see the dashboard quickly.

Do not require boot-time auto-start or full Android kiosk mode for version 1\.

---

## **12\. Error Handling**

Explicitly handle these scenarios:

| Scenario | Required behavior |
| ----- | ----- |
| H10 not worn | Waiting for sensor; retries |
| H10 out of range | Reconnecting; no stale live BPM |
| Bluetooth turned off | Clear explanation and recovery action |
| Permission denied | Explain why permission is needed |
| Tablet moves to treadmill room | Preserve workout and connection when possible |
| Bluetooth disconnects mid-workout | Keep timer running; record No Data |
| App temporarily backgrounds | Continue tracking if supported |
| App process killed | Restore recoverable session |
| Corrupt BLE packet | Ignore packet; do not crash |
| No sensor readings | Show `--`, not zero |
| Sensor connected but no updates | Mark data stale and recover |
| Workout file storage error | Report failure rather than silently claiming data saved |
| Two H10s nearby | Reconnect to saved sensor, not whichever appears first |

Include structured debug logging in debug builds.

Avoid logging personally identifying or sensitive workout information in release builds.

---

## **13\. Testing Requirements**

### **Unit tests**

Implement tests for:

* 8-bit BLE heart-rate parsing  
* 16-bit BLE heart-rate parsing  
* Malformed packet rejection  
* Zone threshold boundaries  
* BPM-to-percentage calculation  
* Zone-duration accumulation  
* Sensor disconnection gaps  
* Paused workout intervals  
* Average HR calculations  
* Session restoration  
* Workout summaries with no valid HR data

### **Integration tests**

Use a simulated `HeartRateSensor` implementation.

Simulated sensor should support:

* Fixed BPM  
* Changing BPM  
* Randomized plausible values  
* Connection failures  
* Disconnection/reconnection events  
* Invalid packet simulation

This is essential so development can proceed without wearing the H10 constantly.

### **Physical device tests**

Once the tablet is available, verify:

1. BLE scan discovers the actual Polar H10.  
2. Initial connection succeeds.  
3. Live BPM changes appropriately during movement.  
4. Closing and reopening the app reconnects.  
5. Turning Bluetooth off and on recovers.  
6. Removing and reattaching the H10 recovers.  
7. A 30-minute workout completes without crashes.  
8. Moving between the gym and treadmill room does not lose saved workout progress.  
9. History remains after restarting the tablet.  
10. A workout with a temporary sensor disconnection has accurate elapsed time and No Data reporting.

A connection dropped due to distance between rooms should recover when the sensor becomes reachable again.

---

## **14\. Acceptance Criteria**

The MVP is complete only when the following are true:

* 	☐	Application builds successfully into an installable APK.  
* 	☐	Application launches successfully on the tablet.  
* 	☐	First-time sensor setup works.  
* 	☐	Real Polar H10 BPM readings are displayed.  
* 	☐	Previously configured H10 reconnects without manual sensor selection.  
* 	☐	BPM becomes visibly stale when readings stop.  
* 	☐	Current training zone is calculated correctly.  
* 	☐	Maximum HR is configurable.  
* 	☐	Workout Start/Pause/Resume/Finish works.  
* 	☐	Workout duration excludes paused time.  
* 	☐	Sensor disconnections do not erase the workout.  
* 	☐	Zone timing does not count disconnected periods.  
* 	☐	Workout summary shows correct statistics.  
* 	☐	Completed workouts are saved locally.  
* 	☐	Workout history survives app restarts.  
* 	☐	Portrait and landscape layouts are usable.  
* 	☐	Screen remains awake during a foreground workout.  
* 	☐	Foreground tracking follows Android lifecycle requirements.  
* 	☐	No internet connection or account is required.  
* 	☐	No cloud infrastructure is necessary.  
* 	☐	App has an attractive, finished interface.  
* 	☐	A real 30-minute workout completes without crashes or data loss.

---

## **15\. Implementation Plan**

### **Phase 1 — Foundation and sensor integration**

**Highest priority**

* Create Android project.  
* Implement permissions.  
* Implement BLE discovery.  
* Connect to Polar H10.  
* Subscribe to Heart Rate Measurement notifications.  
* Build a simple BPM debug screen.  
* Implement simulated sensor.

**Deliverable:** Real-time BPM readings on a physical Android device.

Do not proceed too far with UI development before validating the Bluetooth path.

### **Phase 2 — Dashboard**

* Build full-screen Compose dashboard.  
* Implement zone calculator.  
* Add connection indicator.  
* Add live BPM and zone displays.  
* Add elapsed workout timer.  
* Add start/pause/finish controls.

**Deliverable:** Attractive functional dashboard.

### **Phase 3 — Session persistence**

* Implement versioned workout files and atomic checkpoints.
* Record heart-rate samples.  
* Track zone duration.  
* Compute session summaries.  
* Persist active sessions.  
* Build workout history.

**Deliverable:** Complete recordable workout flow.

### **Phase 4 — Reliability and polish**

* Automatic reconnection.  
* Foreground service integration.  
* Error handling.  
* Portrait/landscape support.  
* Screen awake behavior.  
* Sensor disconnect/reconnect tests.  
* Visual refinement.

**Deliverable:** Ready-to-gift application.

---

## **16\. Future Enhancements**

Architect for these possibilities without implementing them now.

**Priority 2:**

* Custom named workout types  
* More detailed historical charts  
* Export workout data to CSV  
* User-defined training zone thresholds  
* Optional workout audio feedback  
* Multiple user profiles

**Priority 3:**

* Chromecast or TV display  
* Automatically detected workout start  
* Heart-rate-guided interval workouts  
* More advanced Polar H10 metrics  
* Long-term training trends  
* Integration with other fitness ecosystems

Do not compromise MVP reliability by adding these features prematurely.

---

## **17\. Instructions to Coding Agent**

You are responsible for implementing this specification as a complete, runnable native Android application.

Follow these directives:

1. Use Kotlin, Jetpack Compose, native Android BLE APIs, and local JSON workout storage.
2. Prioritize end-to-end functionality on the real tablet.  
3. Do not build a backend or introduce unnecessary cloud dependencies.  
4. Write actual compilable implementation code, not pseudocode.  
5. Keep Bluetooth functionality modular and testable.  
6. Include a simulated heart-rate mode for development.  
7. Implement graceful error handling and automatic reconnection.  
8. Keep the UI minimal, polished, and tablet-oriented.  
9. Use current, compatible Android SDK and library versions.  
10. Do not invent results from tests or hardware that have not actually run.  
11. If the target hardware is unavailable, clearly identify which integration tests remain unverified.  
12. Include instructions for building, installing, and testing the APK.  
13. Implement acceptance criteria before optional enhancements.  
14. Make reasonable technical decisions without repeatedly requesting clarification.  
15. Favor a reliable, finished product over additional complexity.

### **Required final deliverables**

* Full Android Studio project  
* Debug APK  
* Release-ready APK build configuration  
* README with setup instructions  
* Bluetooth troubleshooting guide  
* Automated unit tests  
* Simulated sensor mode  
* Documented settings and configurable constants  
* Summary of completed features and known limitations

**Final product goal:** A custom portable fitness dashboard that feels like a polished dedicated device. The user should be able to wear their Polar H10, pick up the tablet, open the app, and immediately see their live heart rate and training zones, with reliable workout tracking and minimal setup.

