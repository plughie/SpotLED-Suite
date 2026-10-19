# iOS SpotLED Companion

The iOS companion is foreground-only: discover `SpotLED_*` displays, preview
and send text or still images, and report transfer acknowledgments. Text offers
Normal (captured native 16×16 panel glyphs) and Raster modes; rotated text uses
raster pixels. MQTT, scheduled weather, and background notification forwarding
are intentionally excluded from the iOS app.

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
