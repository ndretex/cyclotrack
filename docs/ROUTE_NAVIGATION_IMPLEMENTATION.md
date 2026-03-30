# Route Navigation Guidance Implementation Status

Status: Implemented and actively used in the dashboard

This document records the current shipped state of route replay and route-following guidance in Cyclotrack.

## Current User Flows

Users can start navigation from:

- saved routes in `RouteDetailsFragment`
- past rides in `TripDetailsFragment`

Both flows launch the existing dashboard instead of a separate navigation activity.

## Dashboard Behavior

The dashboard currently operates in two effective modes:

- recording mode
- navigation mode

In recording mode:

- no guidance surface is shown
- the existing metrics-first dashboard remains the primary UI

In navigation mode:

- a guidance surface appears at the top of the dashboard
- the ride is still recorded through the normal ride flow
- the user can stop the ride with an explicit save-or-discard confirmation

## Guidance UI

The in-ride guidance surface currently supports:

- maneuver icon rendering
- distance to next cue
- backend or fallback instruction text
- north indicator
- remaining distance display
- off-route state
- GPS weak state
- arrival state
- an in-dashboard route preview

The current visual direction remains OLED-friendly:

- black background
- white or near-white text and linework
- limited use of accent colors

## Navigation Path Sources

Navigation paths can be built from:

- a saved route using `Route` and `RoutePoint`
- a recorded ride using `Trip` and `Measurements`

Both source types are normalized into a shared `NavigablePath` model before guidance is computed.

## Local Navigation Engine

The local navigation engine remains the baseline path-following implementation.

It currently handles:

- geometry normalization
- live GPS point matching
- upcoming cue detection
- off-route detection
- arrival detection
- preview window generation
- loop-route protection around ambiguous start/finish geometry

Loop-route behavior is now progress-aware:

- routes whose finish is physically close to their start are treated as loop routes
- arrival is suppressed until the rider has actually completed most of the route distance
- initial point matching prefers the beginning of the route when the rider is inside the ambiguous loop start/finish area

## Valhalla Integration

Valhalla support is implemented as an upgrade and reroute backend.

Current behavior:

- the app loads a local navigation path immediately
- it then attempts to replace that path with a Valhalla-derived path in the background
- if Valhalla succeeds, the dashboard switches to backend geometry and maneuvers
- if Valhalla fails, the local engine remains active
- when the rider stays off-route long enough, the app may request a Valhalla reroute

Current reroute behavior:

- reroutes are not sent immediately on a single off-route sample
- reroutes are throttled with a cooldown
- reroutes target a forward rejoin point on the route instead of always targeting the final destination
- loop routes are prevented from rerouting directly to the finish while route progress is still low

Current Valhalla assumptions:

- host configuration comes from `secure.properties`
- requests use `trace_route` for path matching and `route`-style reroute requests through the current service wrapper
- requests send `x-api-key`
- responses are consumed from the OSRM-style response shape exposed by the self-hosted stack

## Route Preview

The dashboard preview is no longer purely schematic.

Current behavior:

- MapLibre preview support is integrated into the dashboard guidance surface
- if a MapLibre style URL is configured, the dashboard can render a map-based preview
- if backend map configuration is unavailable, the rest of the guidance flow still works

## Guidance Text

The guidance header can show:

- Valhalla-provided maneuver text
- generated fallback instructions when backend text is unavailable

Examples:

- `Turn right onto ...`
- `Keep left`
- `Continue straight`
- `Turn around`

## Current Technical Shape

The main implemented pieces are:

- `NavigationModels.kt`
- `NavigationEngine.kt`
- `TripInProgressViewModel.kt`
- `TripInProgressFragment.kt`
- `ManeuverIconView.kt`
- `ValhallaNavigationService.kt`

## Known Limitations

The current implementation still has these gaps:

- there is no explicit on-screen label showing whether guidance is currently local or Valhalla-backed
- reroute destination selection is forward-progress aware, but still heuristic rather than lane- or topology-aware
- loop-route handling is based on start/finish proximity and progress thresholds, not a richer graph model
- the current documentation is route-navigation specific and does not attempt to catalog every broader app feature

## Next Recommended Work

The next high-value improvements are:

- add instrumentation or logging around reroute target selection to validate loop-route behavior in the field
- expose the active guidance engine in debug UI or logs more clearly
- expand route-following tests around loop routes, off-route recovery, and finish detection
