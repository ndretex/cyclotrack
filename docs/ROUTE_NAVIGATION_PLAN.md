# Route Replay And Guidance Architecture Plan

Status: Draft for review before implementation

This document plans the next feature after route import: navigation mode for re-running a past ride or following a saved route.

Cyclotrack already has:

- a `Route` / `RoutePoint` domain
- `GPX` / `FIT` import into saved routes
- a `Routes` tab and route details screen
- an existing ride dashboard launched through `DashboardActivity`

The next feature is:

- re-run a past ride as guidance
- start a saved route as guidance
- launch the existing dashboard in a navigation mode
- keep the normal recording dashboard free of maps and turn guidance
- make the navigation experience extremely minimal and OLED-friendly

## Goal

Allow the user to start navigation from either:

- a saved route
- a previously recorded ride

When navigation starts:

- Cyclotrack should launch the existing dashboard flow
- the dashboard should gain a top guidance surface inspired by the provided reference image

When the dashboard is opened in the normal recording flow:

- the current metric-focused layout remains the default
- no map preview is shown
- no turn guidance is shown

## Product Direction

### 1. One Dashboard, Two Modes

Do not build a second dashboard screen.

Instead, extend the current dashboard with an explicit mode:

- `RECORDING`
- `NAVIGATION`

This keeps the ride experience consistent and lets us reuse:

- the existing activity and navigation graph
- the current brightness / safe-zone behavior
- the current foreground ride tracking service
- the existing measurement layout

### 2. Navigation Is An Overlay On Top Of The Ride Flow

For V1, navigation should still start a new live ride recording.

That is the simplest and most natural fit with the current architecture because the dashboard is already tied to ride tracking and GPS updates.

Important assumption:

- "re-run a ride" means "follow its geometry as a route again"
- it does not mean replaying the old ride's timestamps or historic speed profile

Preview-only navigation is out of scope for V1.
If it is wanted later, it should be designed as a separate feature.

### 3. OLED-First UI

The navigation presentation should optimize for:

- pure black background
- very little color
- very little motion
- as few continuously repainting surfaces as possible

The design target is not a classic navigation app. It's a battery-efficient riding HUD.

## Current Codebase Baseline

Relevant pieces already in the project:

- route storage
  - `Route`
  - `RoutePoint`
- route import
  - `RouteImportService`
- route browsing
  - `RoutesFragment`
  - `RouteDetailsFragment`
- dashboard
  - `DashboardActivity`
  - `TripInProgressFragment`
- live GPS / ride tracking
  - `GpsService`
  - `TripInProgressService`

Also important:

- route and trip detail screens currently render maps with Google Maps
- the in-ride dashboard itself does not currently render a map

That is useful, because it means the dashboard can stay lightweight and custom.

## Desired UX

### Entry Points

Add clear actions from both detail screens:

- `RouteDetailsFragment`
  - `Start route`
- `TripDetailsFragment`
  - `Re-run ride`

Both actions should lead into the same navigation flow.

### Dashboard Behavior

#### Recording Mode

When the user starts a normal ride:

- show the current dashboard
- do not show a map
- do not show turn guidance

#### Navigation Mode

When the user starts a saved route or re-runs a past ride:

- launch the same dashboard container
- add a guidance surface at the top of the screen
- keep the rest of the dashboard familiar and minimal

### Guidance State

During navigation, keep a compact guidance header visible.

When a turn is near, emphasize:

- a large turn arrow
- distance to next maneuver
- optional next-road / cue text if available
- a compact route preview matching the reference image

When no turn is near, keep the header minimal, for example:

- route name
- small remaining distance to next turn or destination
- checkered flag when the next guidance is the destination

### Off-Route State

When the rider leaves the route corridor:

- show a visible off-route warning
- keep the current guidance context visible when possible
- do not require automatic rerouting in V1

### Arrival State

Near the destination:

- keep the destination / final cue visible until the ride is finished

## Visual Design Rules

The navigation mode should use a deliberately reduced palette:

- background: `#000000`
- primary text / arrows: white or near-white
- one muted accent color for route confirmation or rider marker
- one warning color reserved for off-route / errors

Avoid:

- full-screen bright maps
- colorful tiles
- gradients
- decorative chrome
- constant motion

The guidance surface should also respect:

- dashboard safe-zone margins
- burn-in reduction preferences
- current brightness preference behavior

## Recommended UI Structure

### Top-Level Layout

Navigation mode should add a dedicated guidance container above the existing measurements layout.

Recommended regions:

- top guidance container
- current measurement grid
- footer / ride controls

In recording mode, the guidance container should be entirely hidden.

### Guidance Card Content

The top guidance card should be custom-rendered and not depend on a live interactive map.

Recommended content:

- maneuver arrow glyph
- distance label
- simplified local route shape
- current rider marker

This should resemble the provided image:

- black background
- sparse line work
- no dense map labels
- no interactive controls
- purely schematic guidance preview for V1

### Why A Custom Preview Instead Of A Full Map View

For the dashboard itself, a custom schematic preview is preferable to embedding a live map SDK view because it gives us:

- much lower visual noise
- better OLED battery behavior
- total control over styling
- fewer redraws
- no dependency on vendor navigation UI constraints

For V1, this preview should stay purely schematic.
An actual map renderer can be evaluated later as a separate enhancement.

Full map SDK views should stay on:

- trip details
- route details

The dashboard guidance surface should act more like a lightweight instrument panel than a traditional map.

## Architecture

### New Shared Concepts

Introduce the following abstractions:

- `DashboardMode`
  - `RECORDING`
  - `NAVIGATION`
- `NavigationSource`
  - `Route(routeId)`
  - `Trip(tripId)`
- `NavigablePath`
  - normalized geometry that can come from either a saved route or a recorded trip
- `NavigablePoint`
  - latitude / longitude / elevation / cumulative distance / heading
- `NavigationCue`
  - maneuver type, path index, distance from start, optional label
- `NavigationSession`
  - source, current progress, next cue, off-route state, arrival state
- `GuidanceSnapshot`
  - UI-facing state for the fragment

These types should be independent from vendor SDK classes so the app keeps full control over the UI and can swap routing implementations later.

### Path Normalization

Both route-following entry points should normalize into the same in-memory path model.

#### Saved Route Source

Build `NavigablePath` from:

- `Route`
- ordered `RoutePoint[]`

#### Past Ride Source

Build `NavigablePath` from recorded trip measurements that contain location samples.

For trip replay:

- use geometry only
- ignore original pause timing as navigation timing
- remove obvious GPS spikes if needed
- simplify very dense paths before rendering or cue generation

### Cue Generation

Imported `GPX` or recorded rides do not guarantee ready-made turn instructions.

So V1 should generate basic cues from geometry:

- straight
- slight left / right
- left / right
- sharp left / right
- u-turn
- arrive

Cue generation inputs:

- heading delta between segments
- segment length
- noise filtering
- minimum spacing between cues

This should be implemented as Cyclotrack logic, not as a UI concern.

Store cues in memory first.

Persisting them can be added later if startup cost becomes noticeable.

### Route Progress Matching

The navigation engine needs to map live GPS updates onto the active path.

Core responsibilities:

- find the nearest plausible path segment
- maintain a monotonic progress index
- compute along-route distance
- compute remaining distance
- compute distance / ETA to next cue
- estimate off-route status

To avoid unstable behavior:

- reject fixes with very poor accuracy
- use bearing and speed when available
- avoid jumping backward on the path
- avoid declaring off-route from a single bad fix

### Guidance UI State Machine

Recommended UI states:

- `Recording`
- `Guidance`
- `TurnGuidance`
- `OffRoute`
- `Arrival`
- `GpsWeak`

Suggested transition order:

1. if dashboard mode is `RECORDING` -> `Recording`
2. else if GPS confidence is poor -> `GpsWeak`
3. else if off-route -> `OffRoute`
4. else if approaching destination -> `Arrival`
5. else if next cue distance < `300 m` -> `TurnGuidance`
6. else if next cue ETA < `20 s` -> `TurnGuidance`
7. else -> `Guidance`

This logic should live outside the fragment in a navigation-focused state producer or view model layer.

## Battery And Screen Strategy

### Keep GPS Reliable During Navigation

Location tracking should remain anchored in the foreground service layer, not in the dashboard UI.

That means:

- GPS keeps running even when the guidance UI is visually minimal
- navigation logic does not depend on a visible map
- the app can reduce visual clutter without losing route progress

### Minimize Expensive Rendering

To support OLED savings:

- keep the background fully black
- avoid embedding a constantly animating map in the dashboard
- redraw the guidance surface only when route progress materially changes
- keep animations short and meaningful

### Brightness Behavior

The current brightness preference can remain the baseline.

Later, we can add an optional navigation-specific behavior:

- normal baseline brightness during navigation
- optional stronger emphasis for warnings or near maneuvers

That should be phase-two polish, not required for first delivery.

### Vendor Battery Optimization

Across Android vendors, the most reliable foundation remains:

- foreground service for active ride tracking
- proper location permissions
- user education for aggressive vendor battery-killer settings

Do not start with device-specific hacks.

Start with the standard Android foreground-service model and add troubleshooting guidance later if real-world testing shows vendor-specific failures.

## Poor GPS, Tunnels, And Confidence Handling

Navigation quality will depend heavily on not overreacting to noisy fixes.

Recommended behavior:

- maintain the last stable matched segment
- use a short grace window before switching to off-route
- keep the current cue visible during brief signal loss
- show a `GPS weak` state instead of oscillating between cues and rerouting

For tunnel-like situations:

- keep progress anchored to the last good match for a short interval
- optionally extrapolate a short distance using recent speed / bearing
- rematch aggressively once signal returns

Rerouting should not trigger immediately just because GPS disappeared for a few seconds.

## Routing / Navigation Engine Strategy

### Product Constraint

The requirement for this feature is:

- open-source and free where possible
- full control over UI
- no forced classic full-map navigation screen

That changes the SDK choice significantly.

### Candidate Review

#### Google Navigation SDK

Google's official docs confirm that the Navigation SDK supports custom navigation experiences, but they also state that apps using it cannot include the Maps SDK in the same app and must replace it with the Navigation SDK map stack.

That makes it a poor default fit here because:

- Cyclotrack already uses the Maps SDK in trip and route details
- the requirement is not vendor lock-in
- the goal is a highly custom battery-focused UI, not Google's standard navigation surface

#### Mapbox Navigation SDK

Mapbox's official Android navigation docs clearly expose:

- route progress
- active guidance
- rerouting hooks

From a capability perspective it fits the UX well.

From a product / licensing perspective it does not fit the current requirement as cleanly because:

- it is not the open-source-first choice
- it introduces pricing / platform terms

#### TomTom Navigation SDK

TomTom's official docs also expose route progress listeners and turn-by-turn updates.

Like Mapbox, it is a capable proprietary option, but it is not the best architectural match if open-source / free is a hard requirement.

#### MapLibre Native

MapLibre Native is explicitly positioned as an open-source mapping engine with Android support.

This makes it a good candidate when Cyclotrack needs:

- an open-source map renderer
- an optional navigation map surface later

However, MapLibre by itself is not the navigation engine.

It solves rendering, not cue generation or rerouting.

#### Open-Source Routing Engines

For future rerouting, Cyclotrack should keep a pluggable routing abstraction that can later target an open-source engine such as:

- GraphHopper
- Valhalla

Valhalla's official project documentation explicitly describes it as an open-source routing engine for OpenStreetMap data, with offline-oriented architecture and maneuver generation support.

### Recommendation

Recommended architecture:

- do not adopt Google, Mapbox, or TomTom as the primary architecture for this feature
- build a Cyclotrack-owned guidance UI
- build a Cyclotrack-owned path / cue / progress layer
- keep rerouting behind a `RoutingEngine` abstraction

Recommended phased implementation:

- V1
  - no vendor navigation SDK
  - follow local geometry from `Route` or `Trip`
  - generate cues in-app
  - detect off-route and surface the warning immediately
  - do not auto-reroute
- V2
  - add open-source rerouting through a pluggable backend or service
  - evaluate `MapLibre Native` if an open-source rendered map becomes necessary inside navigation mode

This is the cleanest path if "free, open, custom UI" is the priority.

## Proposed Data And Session Model

### Dashboard Launch Arguments

Add navigation-aware arguments to the dashboard launch path, for example:

- `dashboardMode`
- `navigationSourceType`
- `navigationSourceId`

Possible values:

- `dashboardMode = RECORDING`
- `dashboardMode = NAVIGATION`
- `navigationSourceType = ROUTE | TRIP`
- `navigationSourceId = <db id>`

### Optional Future Linkage

Later, when a ride is recorded while following a route, it may be useful to add:

- `Trip.sourceRouteId`
- `Trip.sourceTripReplayId`

That is not required for V1 but should remain easy to add later.

## Implementation Phases

### Phase 1: Navigation Launch And OLED HUD

Scope:

- add route and trip entry points
- add dashboard mode arguments
- hide guidance entirely in recording mode
- show a custom guidance container in navigation mode
- normalize trip / route geometry into one path model
- generate basic cues from geometry
- implement `Guidance` and `TurnGuidance` states

Outcome:

- user can start a saved route
- user can re-run a past ride
- same dashboard is reused
- no live map appears in normal recording mode

### Phase 2: Progress Matching And Off-Route Handling

Scope:

- route matching against live GPS
- confidence / poor GPS handling
- off-route detection
- off-route wake behavior
- destination proximity behavior

Outcome:

- navigation feels stable outdoors
- off-route warning appears promptly when it matters
- tunnels and noisy fixes behave more gracefully

### Phase 3: Optional Rerouting

Scope:

- add `RoutingEngine` abstraction
- integrate an open-source rerouting backend or service
- recompute route when the rider is truly off-route
- refresh cues cleanly after reroute

Outcome:

- off-route can recover automatically instead of only warning the rider

### Phase 4: Polish

Scope:

- navigation-specific brightness behavior
- caching precomputed cues
- optional haptics or audio
- vendor battery troubleshooting UX
- additional route-follow analytics or trip linkage

## File Touchpoints

Likely file changes for implementation:

- `app/src/main/java/com/kvl/cyclotrack/DashboardActivity.kt`
- `app/src/main/res/navigation/dashboard_nav_graph.xml`
- `app/src/main/java/com/kvl/cyclotrack/TripInProgressFragment.kt`
- `app/src/main/res/layout/fragment_trip_in_progress.xml`
- `app/src/main/java/com/kvl/cyclotrack/TripInProgressService.kt`
- `app/src/main/java/com/kvl/cyclotrack/GpsService.kt`
- `app/src/main/java/com/kvl/cyclotrack/RouteDetailsFragment.kt`
- `app/src/main/java/com/kvl/cyclotrack/TripDetailsFragment.kt`
- new navigation-domain files for:
  - path normalization
  - cue generation
  - route matching
  - guidance state production
  - custom guidance rendering

## Testing Plan

### Unit Tests

Add focused tests for:

- cue generation from synthetic paths
- path normalization from route points
- path normalization from trip measurements
- next-cue distance calculations
- dashboard state transitions
- off-route threshold logic

### Replay Tests

Use imported routes and real recorded rides to replay location traces and verify:

- cue ordering
- stable matching
- guidance-to-turn-guidance transitions
- arrival handling

### Manual Scenarios

Manual validation should cover:

- start normal recording dashboard
- confirm no map / guidance appears
- start navigation from saved route
- start navigation from past ride
- long straight segments keep compact guidance visible
- near-turn transitions to emphasized guidance card
- off-route wakes the UI
- poor GPS does not cause immediate false reroutes
- arrival keeps the interface active

## Confirmed V1 Decisions

The following product decisions are now fixed for V1:

- navigation always starts a new recorded trip
- off-route warning is enough for the first release
- the dashboard guidance preview stays purely schematic

Later extensions may still add:

- preview-only navigation modes
- automatic rerouting
- an optional real map renderer inside navigation mode

## Remaining Question

One implementation detail is still worth resolving before development starts:

- should cues stay in-memory only, or should we persist precomputed cues for imported routes?

## Recommendation Summary

The recommended plan is:

- keep one dashboard, not two
- add an explicit navigation mode on top of the current dashboard
- keep recording mode free of guidance and map UI
- render the navigation card as a custom minimal black HUD, not a live interactive map
- normalize both saved routes and past rides into a shared `NavigablePath`
- generate basic maneuver cues inside Cyclotrack first
- treat rerouting as a later pluggable open-source extension rather than the first implementation milestone

This approach fits the current codebase, fits the OLED / battery goal, and avoids locking the app to a vendor navigation UI model.

## Sources

- Google Navigation SDK route experience:
  - https://developers.google.com/maps/documentation/navigation/android-sdk/intro-route-experience
- Google Navigation SDK setup overview:
  - https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview
- Mapbox Navigation SDK overview:
  - https://docs.mapbox.com/android/navigation/overview/
- Mapbox turn-by-turn navigation guide:
  - https://docs.mapbox.com/android/navigation/guides/turn-by-turn-navigation/
- TomTom turn-by-turn navigation guide:
  - https://developer.tomtom.com/android/navigation/documentation/guides/navigation/turn-by-turn-navigation
- MapLibre Native project page:
  - https://maplibre.org/projects/native/
- Valhalla official repository / docs:
  - https://github.com/valhalla/valhalla
