# loopdo_

A local-first Android app for to-dos, habits and a daily journal, with streaks, heatmaps and a home-screen widget. A day can only be ticked off while it is that day, using your own day boundary.

Work in progress. `shared/` holds the domain rules (day boundary, streaks, freezes, offline-tick validation) as a pure Kotlin library used by every part of the app.

```sh
./gradlew :shared:test
```
