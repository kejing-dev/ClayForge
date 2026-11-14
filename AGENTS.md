# 捏塑坊 ClayForge (io.embodit.clayforge)

Shared Space, archetype A: default container = Volumetric window `Studio` (0.6 × 0.55 × 0.6 m, WorldScale.Fixed,
Gravity, uniform resize) with the clay, turntable, tool tray, glaze jars and kiln (ECS); control panel = Planar window
`Panel` (520 × 600 dp, `Placement.right(Studio, 48 dp)`, native TabBar 捏 / 作品 / 设置). SDK BOM 6.1.9.
Design: `spatial-app-factory/apps/clay-forge/design-package.json` (+ prd.html, sample.html).

## Build
`export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home; ./gradlew testDebugUnitTest assembleDebug`
Release: unsigned `assembleRelease` (no signingConfig). Props: `tools/prep_assets.py` (factory venv) turns the decimated
Tripo GLBs (`bin/glb_decimate.py`) into `assets/models/*.glb` (tools split + calibrated, kiln cut into body / lid).

## Code map
- `domain/` pure Kotlin, JVM-tested: `ClayField` (64³ field, 5 mm, mask = cylinder r 0.145), `SurfaceNets` (16³ chunks),
  `Sculpt` (Sculptor: tip ray −z tilted 15°, start gate, add / carve / smooth / pull, mirror, undo 30, budget 55k / 52k),
  `Shapes` (start lumps, 6 samples, 6 glazes), `Layout` (all positions + d35 / bounds / kiln checks), `Export` (GLB, STL,
  thumbnail), `Works` (WorkStore, prefs), `ProcMesh` (turntable, tray, rings, rods), `OneEuro`.
- `scene/` ClayScene (entities, input routing, one frame loop: dynamic mesh ≤ 20 Hz / ≤ 6 chunks, tool fly, turn,
  glaze soak, kiln), ClayChunks (mesh on Dispatchers.Default, upload on main, Mutex), Sounds (CC0, res/raw), Kit.
- `ui/` StudioVolume (SpatialView, tap + drag detectors, chips), PanelWindow (TabBar, exit), Pages, Widgets.
- `Studio.kt` the one controller: every input, button and debug command goes through it.

## Debug broadcast (debug builds; same paths as real input)
`adb -s <serial> shell "am broadcast -a io.embodit.clayforge.DEBUG -p io.embodit.clayforge --es cmd '<cmd>[; <cmd>]'"`
`state S02..S20` (drives each design state through the real flow) · `tool add|carve|smooth|pull [s|m|l]` · `size` ·
`stroke <brush> line|zigzag|circle|spiral [s|m|l] [half]` (moves the tip with the knob, then drags the head at 20 Hz) ·
`release` · `poke` · `movetip <dx> <dy> [half]` · `yaw <deg>` · `turn90` · `drag turn <dx>` · `mirror on|off` · `undo` ·
`glaze [<id>]` · `jar <id>` · `fire` · `back` · `backclay` · `open sample <id>` · `open work <index>` · `new ball|egg|block` ·
`newlump` · `export` · `fill` · `corruptSave` · `closepanel` · `reopen` · `lang zh|en|system` · `mute on|off` ·
`volume 0-100` · `page sculpt|works|settings` · `detail back` · `delete [confirm]` · `confirm exit` · `exit` · `tipInfo` ·
`boundsCheck` (`BOUNDS outside=0`) · `resetOnboarding` · `guide` · `dump` · `probe [seconds] [chunksPerUpdate]`.
Glazes: celadon moonWhite junRed tenmoku persimmon mistBlue. Samples: mushroom catHead snowman monster vase turtle.
Logcat tag `ClayForge` (mirrored to `files/input-log.txt`); every change logs `STATE S05[+S09]…`.

## Screenshot list (QA, design states[])
S01 cold start ≤ 1 s · S02 `pm clear` then launch (or `state S02`) · S03 `state S03` · S04 `state S04` · S05 `state S05` ·
S06 `state S06` (zigzag held half way; `release` ends it; also `stroke carve|smooth|pull zigzag half`) · S07 `state S07` ·
S08 `state S08`, then `yaw 45` · S09 `state S09` · S10 `state S10`, then `jar junRed` · S11 `state S11` + 1.0 s ·
S12 `state S12` + 7 s · S13 `state S13` then `adb pull /sdcard/Download/ClayForge/` · S14 `state S14` (and after `pm clear`) ·
S15 `state S15`, own work → `delete` · S16 `state S16` · S17 `state S17`, `lang en`, `confirm exit` · S18 `state S18` ·
S19 `corruptSave`, force-stop, launch · S20 `state S20` (fill pokes until 55k).

Screenshots: `adb -s <serial> emu screenrecord screenshot <dir>` (2560²); `screencap` tears on this emulator.

## Known facts
- Probe 2026-11-11 (emulator-5562): replaceWithMeshModel 2.5–3 ms per chunk, 12–15 Hz updates during a 10 s add
  stroke, loop 42–51/s; cold build of the ball 0.4–0.5 s. Two updates at once corrupted the shared mesher → Mutex.
- `dragAmount` is the movement since the press (cumulative), px in viewer axes; converted with dpToLength, y flipped.
