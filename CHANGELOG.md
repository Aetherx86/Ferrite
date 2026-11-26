# Changelog

## [0.4.0] - 2026-11-27

- Line avoidance (`RouteOptions.avoidLines`) and waypoint routing (`RoutePlanner.planVia`): exclude disrupted or crowded lines from Pareto rail searches, or route through ordered intermediate stations with automatic same-line leg merging and hour-aware transfer waits.
- Arrive-by departure solver (`RoutePlanner.arriveBy` / `TimedJourney`): computes latest departure times across Pareto options using fixed-point refinement over hour-dependent headway waits.
- Interactive departure-time presets (`지금`, `출근 08:00`, `낮 13:00`, `퇴근 18:30`, `심야 01:00`) in the rail planner screen so riders can preview rush-hour, midday, and late-night waits and service-hour flags directly in the app and web demo.
- Offline visit-order optimizer (`optimizeOrder`) in `TripPlan` using nearest-neighbor seeding and 2-opt local refinement over great-circle distance.
- Fixed CI version-check path for `BuildInfo.kt`, enabled `main` push/PR triggers, and upgraded Gradle wrapper & dependencies via Dependabot PRs #2 and #3.

## [0.3.0] - 2026-11-21

- Rail trips come as a set: the quickest, and each trip that saves a change for a few more minutes. None of the trips offered is beaten on both time and changes by another. The search runs in rounds, one per change, and an automated test checks it against an exhaustive search over every path on hundreds of random networks. Trips that take much longer than the quickest (more than 20 minutes or half as long again, whichever is more) are not offered.
- The wait at a change follows how often the next line runs at that hour and day, as half its usual interval, instead of a flat three minutes. Each change shows its walk and its wait, and the get-off alert counts both. A trip that starts or changes outside the usual 05:30 to 00:30 service is flagged on screen.
- A relaxed transfer pace for slower walking, prams or luggage: walks between platforms are allowed half as long again, plus a minute. The choice is remembered on the device.
- The rail network is checked as it loads. A hop between unknown stations, an impossible run time, a station off the Earth, a repeated id or a colour that cannot be read is left out or replaced and listed, so damaged data gives a smaller network instead of a crash. The shipped data passes with nothing left out.
- Route shapes from walking, driving and world transit services are read by one shared decoder that keeps every complete point of a damaged or cut-short shape instead of crashing. A world transit itinerary with an unreadable time is left out rather than failing the whole answer, and a walking or driving answer with no shape fails with a clear message.
- Line colours also accept the short `#RGB` form.

## 0.2.0

- Driving directions by vehicle: car, light car, electric car, truck with height and weight limits, motorbike and bicycle, with an option to avoid tolls.
- An electric car's battery on arrival, with a stop at a charger added when the battery would run short. Both are estimates.
- Car parks around the destination, one tap from being the place the route ends.
- A taxi fare estimate for the same trip, at Seoul rates with the late night surcharge.
- Trip planning by day: put places in any order and get the best order, arrival times, stays, costs and notes, saved on the device.
- Nearest police stations, hospitals and fire stations as walking destinations, on the well-lit route.
- Works abroad. Places can be searched worldwide, walking and driving work anywhere, public transport covers cities that publish timetables, and a street map opens on any place.

## 0.1.0

First release.

- Platform boards for every urban rail station in Korea, with the next train, moving trains, per-car load estimates, and sound. When a train arrives, the line's color takes over the page.
- Schematic maps for the capital area, Busan, Daegu, Daejeon and Gwangju, generated from open data, and a national map with real track shapes and administrative borders.
- Rail trip planning that works offline, with measured transfer walks, fares for the capital area, and a get-off alert.
- Walking directions with fastest, step-free and well-lit options.
- Incheon Airport departure and arrival board.
- Bus arrivals for Seoul and other cities.
- Initial consonant search, favorites, recent stations and nearby stations.
- Android APK, an iOS project to build yourself, and a web edition.
