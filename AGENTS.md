# 捏塑坊 ClayForge (io.embodit.clayforge)

Full Space, design container STAGE_MIXED (r4/r5, 2026-11-14): one Mixed Stage holds the bench W (walnut board 1.24 ×
0.95 m, placed with `Layout.placeUpright`: 0.62 m ahead, top 0.58 m under the eye, clamp 0.55–1.15 m), the turntable on
a carriage (it slides into the kiln), clay, tray, glaze jars, the front-door electric kiln and the bench chips; the
control window is one Planar WindowContainer 520 × 460 dp with the native TabBar 课 / 自由捏 / 作品 / 设置, opened from
the Stage through the level gate (`platform/WindowCtl`). SDK BOM 6.1.9. Design: `spatial-app-factory/apps/clay-forge/design-package.json`.

## Build
`export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home; ./gradlew testDebugUnitTest assembleDebug`
Release: unsigned `assembleRelease` (no signingConfig). Props: `tools/prep_assets.py` (factory venv) turns the decimated
Tripo GLBs (`bin/glb_decimate.py`) into `assets/models/*.glb` (tools split + calibrated, kiln cut into body / lid).

## Code map
- `domain/` pure Kotlin, JVM-tested: `ClayField` (64³, 5 mm), `SurfaceNets`, `Sculpt` (Sculptor: relative strokes for
  eye-hand / pointer, absolute strokes for hand pinch / controller tip, r5 scrape plane at press, pull grabs at press +
  3D, poke nub, mirror, undo 30, budget 55k / 52k), `Lessons` (4 lessons, `StepPlayer` plays a step deterministically,
  `LessonPlan` offline start / end fields + score), `Layout` (bench frame, kiln, chips, lesson panels, placeUpright,
  bench snap, window occlusion), `KilnMesh` (closed procedural kiln parts), `ProcMesh` (rounded box, helix, lathe …),
  `LevelGate` + `RecallSampler`, `Shapes`, `Export`, `Works`.
- `platform/` `Tracking` (HeadTracker, Controllers: poses / trigger / B-Y / stick, Hands: pinch with hysteresis),
  `WindowCtl` (level-gated open / reopen / put away), `CfLog`.
- `scene/` `ClayScene` (everything per frame in `ClayFrameSystem`: three input channels, mesh ≤ 20 Hz, kiln timeline,
  lesson player, chips), `ClayChunks`, `Sounds`, `Kit`, `input/BoardInput` (factory template, system pointer).
- `ui/` `StageScreen` (SpatialView + attachment panels: chips, caption, transport, score, HUD; LIMB Hidden while a
  tool rides a controller), `PanelWindow` (TabBar, exit closes window + Stage), `Pages`, `Widgets`.
- `Studio.kt` the one controller: every input, button and debug command goes through it.

Inputs (design container.inputs): hand pinch (thumb + index < 1.5 cm, release > 3 cm) — pinch a tray tool to pick it,
pinch near the clay to sculpt (tip = pinch point), the turntable rim to turn, the bench rim to move; controller — point
at a tray tool + trigger: the tool rides the controller (tip 0.10 m ahead, upper-limb rendering Hidden), touch the clay
+ hold trigger to sculpt, B / Y = put back, stick x = turn 90° / hold 60°/s, stick y = size; eye-hand / hand ray /
controller pointer — the system pointer on the tool head (relative stroke, gain 1–2.5, depth ray from the head), knob,
ring, clay proxy, bench rim. Log `SRC hand|ctrl|gaze|handray|pointer`.

## Debug broadcast (debug builds; same paths as real input)
`adb -s <serial> shell "am broadcast -a io.embodit.clayforge.DEBUG -p io.embodit.clayforge --es cmd '<cmd>[; <cmd>]'"`
- states: `state S05..S26` (S05–S20 free play as before; S21 recall; S22 bench move held; S23 learn; S24 watch; S25 try; S26 done)
- lessons: `lesson <n>` (pick page of lesson n) · `lesson <n> step <i> t <0–1>` (watch, paused there) · `lesson start` ·
  `lesson try [i]` · `lesson inject [half]` (the demo moves fed to your clay, scored) · `lesson transport 0..3` ·
  `lesson done` (inject every step → S26) · `lesson ghost` · `lesson glaze` · `lesson next` · `lesson pick ±1`
- input channels: `hand x y z 0|1` / `hand off` (right-hand pinch, bench metres) · `ctrl x y z 0|1` / `ctrl off`
  (tool tip, trigger) · `ctrlmount` / `ctrlback` · `handstroke <brush> [half|out]` · `ctrlstroke <brush> [half|out]` ·
  `stick x y` (one push) · pointer path: `stroke <brush> line|zigzag|circle|spiral [s|m|l] [half]`, `poke`,
  `movetip dx dy`, `drag turn dx`
- bench / window: `benchmove dx dy dz [hold]` · `benchend` · `recall` · `closepanel` · `reopen`
- free play: `tool <b> [s|m|l]` · `size` · `yaw <deg>` · `turn90` · `mirror on|off` · `undo` · `glaze [<id>]` · `jar <id>`
  · `fire [hold <sec>]` · `back` · `backclay` · `open sample <id>` · `open work <i>` · `new ball|egg|block` · `newlump` ·
  `export` · `fill` · `corruptSave`
- settings: `lang zh|en|system` · `mute on|off` · `volume 0-100` · `page learn|free|works|settings` · `howto [off]` ·
  `confirm exit` · `exit` · `detail back` · `delete [confirm]`
- checks: `dump` · `tipInfo` · `boundsCheck` (`BOUNDS outside=0`) · `resetOnboarding`

Logcat tag `ClayForge` (mirrored to `files/input-log.txt`); prefixes STATE SRC TOOL SIZE STROKE LESSON PINCH CTRL LIMB
BENCH PLACE RECALL WINDOW DIAG TRACK FRAME KILN.

## Screenshot list (QA, design states[])
S01 cold start 1 s + 3 s · S05 `state S05` · S06 `state S06` (+ `stroke carve|smooth|pull zigzag half`) · S07 · S08 ·
S09 · S10 (`jar junRed`) · S11 `fire hold 2.9` / `fire hold 3.9` · S12 `state S12` + 7 s · S13 · S14 · S15 · S16 · S17
(zh / en, `howto`, `confirm exit`) · S18 `closepanel` · S19 `corruptSave` + restart · S20 `fill` · S21 `recall` + 0.5 s ·
S22 `benchmove 0.2 0 0 hold` · S23 `lesson 3` · S24 `lesson 1 step 2 t 0.6` · S25 `lesson try` (0 %), `lesson inject`
(≥ 90 %), `lesson inject half` (30–70 %) · S26 `state S26`. Every tool on the clay through the hand / controller channel:
`handstroke add|carve|smooth|pull`, `ctrlstroke …`.

Screenshots: `adb -s <serial> emu screenrecord screenshot <dir>` (2560²); `screencap` tears on this emulator.

## Known facts
- Probe 2026-11-11 (emulator-5562): replaceWithMeshModel 2.5–3 ms per chunk, 12–15 Hz updates during a 10 s add
  stroke, loop 42–51/s; cold build of the ball 0.4–0.5 s. Two updates at once corrupted the shared mesher → Mutex.
- `dragAmount` (SDK 6.1.9) is the per-event delta; `scene/input/BoardInput` sums it (DragTotal, reset on press) before `onDragMove(total)`. px in viewer axes; dpToLength, y flipped.
