# Routes, GPX, And FIT Import Architecture Plan

Status: Implemented, with historical planning sections retained

This document supersedes the earlier idea of importing route/activity files directly into the existing `Trip` model as the default V1 approach.

## Current Reality Check

The route domain is now implemented in the app.

The app currently supports:

- a top-level `Routes` screen in the main bottom navigation
- route import into the dedicated `Route` and `RoutePoint` domain
- route details with map and derived route stats
- navigation launch from saved routes
- navigation replay launch from past rides

The current bottom navigation is:

- `Stats`
- `Record`
- `Rides`
- `Routes`
- `Settings`

`Profile` is no longer a bottom-nav item. It is now accessed from inside Settings.

The sections below are still useful as architectural rationale, but some phrasing reflects the original implementation plan rather than a future roadmap.

## Goal

Introduce a separate `Routes` domain for imported or saved planned rides, while keeping `Trip` focused on actual recorded activities.

This should support:

- a top-level `Routes` screen in the main navigation
- importing `GPX` and `FIT` files into saved routes
- preserving a clean path for navigation that can follow either:
  - a saved route
  - a previously recorded trip

## Core Product Direction

- `Trip` remains the model for real rides the user actually completed.
- `Route` becomes the model for imported or saved planned rides.
- `GPX` and `FIT` import should target `Route` first, not `Trip`.
- Future navigation should operate on a shared path abstraction, not on the `Trip` table directly.

## Why Not Add A `tripType` To `Trip`

The current `Trip` domain is tightly coupled to:

- recorded timestamps and ride lifecycle state
- `Measurements` and `TimeState`
- analytics and ride-history queries
- split generation and trip detail charts
- Google Fit / Strava sync behavior
- ride export behavior

Putting planned routes into `Trip` would require exceptions across many existing flows and would make the model harder to reason about over time.

## Proposed Domain Split

### 1. Real Trips

Keep the existing `Trip`-based model for:

- rides recorded inside Cyclotrack
- ride history
- analytics
- ride sync/export
- charts and ride details

### 2. Saved Routes

Add a separate route domain for:

- GPX or FIT files imported from Komoot, Garmin, Strava exports, or similar tools
- planned rides the user wants to save and possibly follow later
- route geometry that may or may not contain timestamps
- future navigation and route-following use cases

## Proposed Data Model

### `Route`

Recommended metadata fields:

- `id`
- `name`
- `description` / notes
- `source` or import source
- `createdAt` / imported timestamp
- optional summary fields:
  - total distance
  - ascent / descent
  - bounds
  - hasTimestamps

### `RoutePoint`

Recommended geometry fields:

- `id`
- `routeId`
- `sequence`
- `latitude`
- `longitude`
- `elevation`
- optional `timestamp`

Important point:

- timestamps should be allowed but not required
- route geometry must still be valid when the GPX file is untimed

### Future-Friendly Optional Models

Not required for V1, but worth planning around:

- `RouteCue`
  - turn instructions / maneuvers / distance along route
- route-to-trip linkage
  - for example `trip.sourceRouteId`

## File Import Strategy

### V1 Recommendation

Import `GPX` and `FIT` files into `Route`, not `Trip`.

This lets us support:

- timed activity files
- untimed planning files
- route-shaped exports from external planning apps

### Supported File Formats

- `GPX`
- `FIT`

### GPX Structures

For `GPX`, the importer should be able to read:

- `<trk>/<trkseg>/<trkpt>`
- `<rte>/<rtept>`

Timestamps should be treated as optional metadata, not as a requirement for import success.

### FIT Structures

For `FIT`, the importer should extract route geometry when the file contains usable location points.

Examples of acceptable FIT sources for the route domain:

- course-like FIT exports
- activity FIT exports that include position samples

For the route domain, timestamps from FIT should be treated as optional metadata after parsing, just like GPX:

- if present, preserve them
- if absent or not meaningful for route storage, keep the route valid without them

### Normalized Import Model

To avoid making storage depend on any one external format, the importer should normalize both formats into the same internal model before persistence.

Recommended internal types:

- `ImportedRoute`
- `ImportedRoutePoint`

Both the GPX parser and the FIT parser should map into that shared model, and only then write to `Route` / `RoutePoint`.

### Import Classification

To keep behavior predictable, the first import entry point should be explicit:

- importing from the `Routes` screen creates a `Route`
- the selected file may be either `GPX` or `FIT`

A separate future flow may later import a timed activity file as a completed `Trip`, but that should be designed as a distinct feature rather than overloaded into the first route import feature.

## UI / Navigation Direction

The `Routes` screen is already present as a top-level destination in the main bottom navigation.

Recommended UI label:

- `Routes`

Possible alternatives:

- `Saved Routes`
- `Courses`

`Routes` is the clearest and most future-proof name.

### Expected Top-Level Navigation

- `Stats`
- `Record`
- `Rides`
- `Routes`
- `Settings`

Profile access lives under Settings.

### Route Screens

V1 likely needs:

- a route list screen
- a route details screen

The route details screen should be route-first, not trip-first:

- map
- route name and metadata
- distance and elevation summary
- import source if useful

It should not assume:

- ride duration
- average speed
- splits
- heart rate / cadence / speed charts
- sync/export actions intended for completed activities

## Shared Geometry Direction

Even though `Trip` and `Route` should stay separate, they should converge at a shared geometry layer.

Longer term, Cyclotrack should have a shared in-memory concept such as:

- `Path`
- `RouteGeometry`
- `NavigablePath`

That model should be constructible from either:

- recorded trip measurements
- saved route points

This is the cleanest setup for future navigation without forcing `Trip` and `Route` into the same storage model.

## Main Implementation Challenges

### 1. Keeping Domain Boundaries Clean

Routes must not leak into:

- ride analytics
- trip search
- cleanup queries
- Strava / Google Fit sync
- ride export flows

### 2. Avoiding UI Reuse Traps

`TripDetailsFragment` is designed around completed activity data.

A route detail surface should be separate instead of trying to reuse trip details with many hidden sections.

### 3. GPX Flexibility

Route import should be tolerant of:

- untimed files
- timed files
- route-based GPX
- track-based GPX
- FIT files with usable position samples

This is broader than the earlier "timestamped track only" assumption.

### 4. Shared Map / Geometry Utilities

Distance, elevation, map rendering, and future route-following logic should be written so they can operate on either:

- `Measurements`
- `RoutePoint`

### 5. Performance For Large Imports

Planning tools may export dense geometry.

We may eventually need:

- point decimation for rendering
- lightweight summary calculations
- efficient route detail queries

### 6. Future Linkage Between Trips And Routes

Later, the user may:

- ride a saved route
- re-run a past trip as navigation guidance

It will help if we plan for optional associations between the two domains without making them the same thing.

## Recommended Phased Rollout

This phased rollout is now mostly historical because the route domain and its primary UI are already implemented.

### Phase 1

Establish the new `Routes` domain:

- entities
- DAO/repository
- new tab/screen
- route list and route details UX

### Phase 2

Add `GPX` and `FIT` import into `Route`:

- support route and track GPX
- support FIT files with usable geometry
- timestamps optional
- calculate route summary fields

### Phase 3

Add shared geometry abstractions for both domains:

- route geometry
- trip geometry
- common map/profile helpers

### Phase 4

Design later features on top of that foundation:

- navigate a saved route
- navigate a past trip
- optional route/trip linkage
- optional turn-cue support

## Explicit Non-Goals For This Plan

- turn-by-turn instructions
- next-turn dashboard widgets
- route snapping or map matching
- reworking the current ride recording model
- merging planned routes into the `Trip` table

## Expected File Touchpoints Later

This is not an implementation checklist yet, but likely areas include:

- `app/src/main/res/menu/menu_main_activity_bottom.xml`
- `app/src/main/res/navigation/cyclotrack_nav_graph.xml`
- `app/src/main/java/com/kvl/cyclotrack/MainActivity.kt`
- new route entities / DAO / repository files
- new routes list and details fragments
- GPX import parser files
- FIT import parser files

## Recommendation Summary

The right architectural move is:

- keep `Trip` as the real-ride domain
- introduce a separate `Route` domain
- import `GPX` and `FIT` into `Route`
- build future navigation on a shared path abstraction that can come from either a route or a trip

This keeps the current ride system stable while creating a clean foundation for planned routes and later navigation features.
