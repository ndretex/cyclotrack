# Google Fit Sync Guide

This document explains how Google Fit is implemented in Cyclotrack.

## What Google Fit Is Used For

- Sync ride sessions and ride data to Google Fit
- Remove ride data from Google Fit
- Sync biometrics from Google Fit (height, weight, resting heart rate)

## Main User Interactions

## 1) Connect Google Fit (Settings)

- User opens Settings and taps Google Fit connect option.
- App opens Google account/permission flow.
- If accepted:
  - Google Fit becomes connected.
  - App can read/write allowed data.
- If cancelled/denied:
  - App stays disconnected.

## 2) Disconnect Google Fit (Settings)

- User opens Settings and taps disconnect.
- App shows confirmation dialog.
- User can optionally remove Cyclotrack data from Google Fit.
- Result:
  - Auth tokens/permission state are cleared locally.
  - Optional data removal worker runs if selected.

## 3) Open Ride Details Screen

- App does not auto-force Google Fit account picker anymore.
- Screen opens normally.
- Sync/Unsync menu visibility depends on local sync status and permission state.

## 4) Manual Sync from Ride Details

- User taps `Sync` from ride details menu.
- App enqueues `GoogleFitCreateSessionWorker` for that ride.
- App waits for trip sync status update and then shows popup:
  - Success: `Ride synced successfully.`
  - Failure: `Sync failed, try again.`

## 5) Unsync Ride from Ride Details

- User taps `Unsync` from ride details menu.
- App removes ride data/session from Google Fit.
- Local trip status is set to `REMOVED` when successful.

## 6) Delete Ride That Is Already Synced

- If ride is synced and user tries to delete:
  - App uses unsync+delete flow when permissions are available.
  - If not signed in/authorized, app asks user to sign in first.

## Background Behaviors

## 1) Auto Trip Sync

- Trigger: `MainActivity` resume, only when permissions already exist.
- Worker: `GoogleFitSyncTripsWorker`
- Behavior:
  - Upload unsynced rides
  - Update dirty rides
  - Mark failed rides as `FAILED` if sync errors happen

## 2) Auto Biometrics Sync

- Trigger: `MainActivity` resume, when user setting is enabled and permissions exist.
- Worker: `GoogleFitSyncBiometricsWorker`
- Updates local preferences from Google Fit:
  - Height
  - Weight
  - Resting heart rate (if enabled)

## Sync Status Model

Each trip has a Google Fit sync status:

- `NOT_SYNCED`: not sent yet
- `SYNCED`: synced successfully
- `FAILED`: sync attempt failed
- `REMOVED`: removed from Google Fit
- `DIRTY`: trip changed after sync, needs update sync

These statuses are used to decide which menu actions are shown.

## Key Implementation Files

- `app/src/main/java/com/kvl/cyclotrack/util/GoogleFitUtilities.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitApiService.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitCreateSessionWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitUpdateSessionWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitSyncTripsWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitDeleteSessionWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/GoogleFitSyncBiometricsWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/TripDetailsFragment.kt`
- `app/src/main/java/com/kvl/cyclotrack/AppPreferencesFragment.kt`

## Notes

- Google Fit account checks now use non-interactive last signed-in account lookup.
- This prevents unwanted account picker prompts during normal navigation.
