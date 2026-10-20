# iOS SpotLED Companion

The iOS companion follows Android's foreground send contract: discover/select
`SpotLED_*` displays, send text or images, and report transfer acknowledgments.
It includes the same text colors/effects, five speeds, Normal/Raster choice,
themes, About credits, image transforms, remembered display, send-time discovery,
and still/animated image previews. Rotated text uses raster pixels. The app uses
the same launcher artwork as Android.

Animated GIFs are sampled to two frames to match Android and fit the observed
full-color transfer budget. Preview and send use those same transformed frames
at the same shared 250–1,000 ms interval. Input is capped at 50 MB and downsampled
to at most 4 MP before panel rasterization. Device capabilities are not queried
yet; this implementation retains the Android app's tested 64×16 limits. MQTT,
clock/weather, background notification forwarding, and any other background BLE
work are intentionally Android-only.

## Build

`project.yml` is the shared XcodeGen source; the generated
`SpotLEDCompanion.xcodeproj` stays local so each contributor can use their own
signing team. Generate it with `xcodegen generate --spec ios/project.yml`, open
it in Xcode, select the connected iPhone, and choose your own Apple Developer
team for signing. The app requests Bluetooth permission when **Find SpotLED
displays** is tapped and connects only during a send.

Normal text uses native CharacterString transfers and the captured 16×16 glyphs
shared with Android and Python, with a compact fallback for unsupported
characters. Raster text and still images use 64×16 RGB frames with `contain`,
`cover`, or `stretch` fitting. GIF animation and background features are not
included yet. There is no shared iOS release binary: each contributor selects
their own signing team and builds/deploys locally.
