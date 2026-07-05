# cluster3d-core

Apache-2.0 shared library for an interactive **3D point-cloud viewer of clustered cells** inside
QuPath. This is the "CoreLib" (Rueden CoreLib/HostPlugin pattern): it holds the reusable viewer
code so multiple host frontends can embed it -- the standalone GPL
[`qupath-extension-cluster-3d-navigator`](../qupath-extension-cluster-3d-navigator) shell (a
Stage), and (later) a QP-CAT tab.

- Coordinates: `io.github.uw-loci:cluster3d-core:0.1.0`
- Package: `qupath.ext.cluster3d`
- Produces a **normal library jar** (no shadow, no `QuPathExtension` service file -- the host
  never loads it as an extension).
- License: **Apache-2.0**. It copies no QuPath GPL source; it is Mike Nelson's own code plus a
  few helpers adapted from **Apache-2.0 QP-CAT** (see `NOTICE`). Apache-2.0 flows into either
  frontend without forcing GPL.

## Key entry point

`qupath.ext.cluster3d.ui.Cluster3DNavigatorPane` -- a host-agnostic `BorderPane` with the whole
viewer UI + logic. Small public API:

- `Cluster3DNavigatorPane(QuPathGUI)` -- construct (registers follow-active-image listeners).
- `titleProperty()` -- live "Cluster 3D Navigator - {image}" title to bind a Stage/Tab title to.
- `initialLoad()` -- call once after the pane is shown (runs the first read; prompts the
  image-subset picker in project mode).
- `reload()` -- re-read + rebuild the cloud.
- `dispose()` -- remove listeners, release cached crop servers, stop the thumbnail loader.

Drop it in a `Stage` or a `Tab`; the host owns window geometry, titling, and lifecycle.

## Build

Requires **JDK 21**. Publish to Maven Local so host frontends can resolve it:

```bash
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 publishToMavenLocal test
```

QuPath + JavaFX are `compileOnly` (provided by the QuPath host at runtime). Consumers should
depend on this library **non-transitively** and shade it, letting the host supply QuPath/JavaFX.

## Tests

Pure, headless-safe logic is unit-tested here: `Camera3D`, `PickGrid`, `AxisAutoDetect`,
`DetectionReader` (numeric-filter / measurement-union / `buildPointCloud` / `normalizeShared`),
`Cluster3DNavPreferences.axisKey`/`shortHash`, and `PointCloudView.planLod`. Live render /
gestures / pick / hover / thumbnails / dark-mode / HiDPI need WSL smoke + Windows.
