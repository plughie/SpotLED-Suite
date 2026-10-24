# iOS SpotLED Companion

The iOS companion follows Android's foreground send contract: discover/select
`SpotLED_*` displays, send text or images, and report transfer acknowledgments.
It includes the same text colors/effects, five speeds, Normal/Raster choice,
themes, About credits, image transforms, remembered display, send-time discovery,
and still/animated image previews. Image rotation/fit are image-only. Text has
Landscape and Portrait modes; Portrait rotates each native 16×16 glyph 270°
clockwise in its cell and sends the full string through WordStock/CharacterString
so firmware scrolling effects still work. The preview is the initial 64×16
viewport. The app uses
the same launcher artwork as Android.

Pinch directly on the 64×16 image preview to zoom and drag to pan. The preview
stays visible during reprocessing and updates to match the frame that will be
sent; Reset crop returns to the centered fit. The optional still-image vertical loop fits the image to 64
pixels wide, streams successive 16-row viewports at 250 ms with Scroll Up, and
loops until **Cancel transfer**. Animated GIFs stay on their normal bounded
animation path. This foreground mode holds BLE only while running.

Animated GIFs are sampled to two frames to match Android and fit the observed
full-color transfer budget. Preview and send use those same transformed frames
at the same shared 250–1,000 ms interval. Input is capped at 50 MB and downsampled
to at most 4 MP before panel rasterization. Before sending, the app makes
read-only BLE queries for live screen dimensions/color format, animation limits,
and receive capacity; it rejects unsupported formats/dimensions and payloads
that exceed the device's reported capacity before changing display content.
RGB rendering remains 64×16. MQTT,
clock/weather, background notification forwarding, and any other background BLE
work are intentionally Android-only.

The About panel shows the app version/build number. Text and image previews are
available before sending; native text preview is a static initial viewport, not
firmware marquee simulation. The text preview updates live, and **Send text**
immediately sends the current preview. The keyboard can be dismissed with Done
or by scrolling. If Bluetooth access was denied, enable it under iOS Settings →
Privacy & Security → Bluetooth.

## Build

`project.yml` is the shared XcodeGen source; the generated
`SpotLEDCompanion.xcodeproj` stays local so each contributor can use their own
signing team. Generate it with `xcodegen generate --spec ios/project.yml`, open
it in Xcode, select the connected iPhone, and choose your own Apple Developer
team for signing. The app requests Bluetooth permission when **Find SpotLED
displays** is tapped and connects only during a send.

Normal text uses native CharacterString transfers and the captured 16×16 glyphs
shared with Android and Python. Uncaptured BMP glyphs use the same compact 3×5
fallback scaled to 16×16 while preserving the original codepoint; non-BMP native
text is rejected. The text screen reports encoded packet sizes; native text is
limited to 154 characters by the 7,280-byte font-payload cap. Raster text uses
one fixed 3,126-byte 64×16 RGB frame. Still images use `contain`,
`cover`, or `stretch` fitting. GIF animation and background features are not
included yet. There is no shared iOS release binary: each contributor selects
their own signing team and builds/deploys locally.
