# Runs addon

Open **Runs** from the Mac sidebar, or **Workouts → Runs** on iPhone and Android.
Select **Start run** to record an outdoor run. A heart-rate sensor is optional. If another workout
is active, the button opens that session instead of replacing it.

The addon uses NOOP's native design and existing workout history. It provides:

- Live GPS route, current coordinates, distance, pace and location status.
- Pause/resume with separate route segments, so movement during a pause is not drawn as a run.
- Run history, 7/30/90-day summaries and distance by calendar week.
- Kilometre or mile splits and a pace chart for newly recorded GPS runs.
- The existing workout heart-rate, effort and route-export tools.

Location permission is requested when recording starts. iPhone records in the background while the
screen is off; Android uses the existing foreground workout service. Recording stops when the
workout ends or is discarded. A run lasting at least ten seconds can be saved with its duration
even when location permission is denied; missing distance and pace are shown as unavailable.
Most Macs lack GPS, so recording a route there depends on available location services.

GPS pace and splits start at the first accepted fix and exclude explicit pauses. The workout's
active-time total includes time spent waiting for that first fix. Average pace uses recorded GPS
timing when available, otherwise the distance and duration supplied by the imported workout.
Split boundary times are interpolated between actual GPS samples. Legacy routes with no timing
remain viewable and do not receive invented splits. After a recording interruption, no connecting
distance is guessed; the gap may reduce measured distance and affect pace.

Run timing and routes are stored locally. Apple reuses MapKit for the map background, which may
fetch map tiles when they are not cached. Android's route display is an offline plot, without street
tiles. Completed timed routes are bounded to the newest 400 entries; workout summary history
remains in the existing database. Active routes are checkpointed for app restart recovery.

GPX exports preserve route segment breaks. FIT export is offered only for continuous routes.
The existing export format interpolates point timestamps over the workout window; the in-app
split analysis uses the recorded GPS timing.

## Device check

1. Open Runs without a connected strap and start a run. Confirm the location prompt and GPS status.
2. Outdoors, wait for a fix and run a known route. Confirm position, distance and pace update.
3. Pause, move elsewhere, then resume. Confirm the map shows separate segments and paused movement
   does not add distance.
4. Lock the screen for several minutes, then reopen. Confirm background GPS points are retained.
5. End and save. Reopen the run and check distance, splits, units and the pace chart.
6. Repeat with location denied. Save after ten seconds and confirm duration is retained while
   distance, route and pace stay unavailable.

GPS accuracy and background execution need a physical phone check. Unit tests exercise measured
timing, pause accounting, restart checkpoints, filtering, summaries and segmented GPX export.
