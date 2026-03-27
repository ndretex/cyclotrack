# Strava Sync Guide

This document explains how Strava sync is implemented in Cyclotrack.

## What Strava Is Used For

- Upload rides from Cyclotrack to Strava as FIT activities
- Keep Strava auth tokens refreshed
- Disconnect Strava account from Cyclotrack

## Main User Interactions

## 1) Connect Strava (Settings)

- User opens Settings and chooses Strava connect.
- App opens Strava OAuth screen in browser/app.
- After approval, Strava redirects back to Cyclotrack.
- App exchanges auth code for access/refresh tokens.
- Result:
  - Strava is connected.
  - Sync can run in background.

## 2) Disconnect Strava (Settings)

- User opens Settings and chooses disconnect.
- App shows confirmation dialog.
- App calls Strava deauthorize endpoint.
- On success, local Strava tokens are removed.

## 3) Automatic Ride Sync to Strava

- Trigger: `MainActivity` resume when Strava is connected.
- Worker: `StravaSyncTripsWorker`
- Behavior:
  - Finds unsynced rides
  - Exports ride to FIT
  - Uploads to Strava
  - Updates local status per ride

## 4) Manual/Per-ride Strava Sync Path

- Code supports per-ride worker (`StravaCreateActivityWorker`) for single-ride upload flow.
- In current ride-details menu logic, Strava-specific manual action is currently not the primary path.
- Main active sync path is background unsynced-ride worker.

## Token and Auth Behavior

## 1) Token Exchange

- `StravaTokenExchangeWorker` and `updateStravaAuthToken(...)` handle:
  - auth code exchange
  - refresh token exchange
- Tokens stored in shared preferences:
  - access token
  - refresh token
  - expiry timestamp

## 2) Token Refresh

- Before upload, app checks expiration.
- If token is close to expiry or expired, app refreshes token automatically.
- If refresh fails, sync fails and auth may be cleared depending on response.

## Sync Status Model

Strava uses the trip `stravaSyncStatus` field with same enum type:

- `NOT_SYNCED`
- `SYNCED`
- `FAILED`
- `REMOVED` (less common in Strava flow)
- `DIRTY` (trip edited after sync)

When a synced ride is edited, status can become `DIRTY`, so it is eligible for resync/update handling.

## Rate Limit Behavior

- Strava responses include rate-limit headers.
- App calculates next allowed sync window and stores it.
- If limit is exceeded:
  - app throws `TooManyRequests`
  - worker returns retry behavior
  - sync resumes later

## Error Behaviors

- `401/403`: treated as auth failure
- `429`: treated as rate-limit error
- `5xx`: treated as temporary failure (status handling keeps ride eligible for retry)
- Other failures: marked `FAILED`

## Key Implementation Files

- `app/src/main/java/com/kvl/cyclotrack/util/StravaUtilities.kt`
- `app/src/main/java/com/kvl/cyclotrack/StravaTokenExchangeWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/StravaSyncTripsWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/StravaCreateActivityWorker.kt`
- `app/src/main/java/com/kvl/cyclotrack/AppPreferencesFragment.kt`
- `app/src/main/java/com/kvl/cyclotrack/PreferencesActivity.kt`
- `app/src/main/java/com/kvl/cyclotrack/MainActivity.kt`

## What Users See On Screen

- Settings shows Connect/Disconnect Strava state.
- After connect, background sync may upload rides automatically.
- User may see rides appear in Strava after sync completes.
- If auth or rate-limit issues happen, sync may silently retry in background unless surfaced by other UI flows.
