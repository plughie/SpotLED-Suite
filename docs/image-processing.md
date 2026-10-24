# Image and GIF processing contract

## Animated GIFs

- Decoders render frames according to GIF disposal/compositing rules, then flatten
  transparency against opaque black before converting to RGB. The preview uses
  the exact transformed frames sent to the panel.
- Sampling is uniform over the animation timeline and retains the first and last
  source frames when more than one frame is sent. Current Android and iOS flows
  deliberately send at most two frames; Python uses the live frame and packet
  limits and may send more when the target accepts them.
- Use the whole source-cycle duration divided by the number of frames sent for
  one shared panel interval, clamped to 250–1,000 ms/frame. Source delays below
  20 ms are normalized to 20 ms; zero/missing delays default to 100 ms. The
  transfer report/UI states original and sent frame counts and the chosen
  interval. This bounded cadence avoids rapid full-panel flashing.
- A transfer acknowledgment confirms receipt only. Physical rendering still
  requires visual validation.

## Resampling policy

Python uses area-weighted averaging when reducing pixels; Android/iOS use
nearest-neighbor sampling. Area averaging is the Python CLI default because it
preserves aggregate color and thin features in small-downscale previews. Mobile
uses nearest-neighbor for crisp LED-sized pixel art and lower memory/CPU cost.
Consequently photographs and fine patterns may differ slightly across platforms;
rotation, contain/cover/stretch geometry, black transparency matte, and final
64×16 dimensions are kept consistent. This is an intentional trade-off, not a
claim of pixel-identical previews.

## Pan/zoom viewport and vertical scrolling

Android and iOS expose 1–4× image zoom and normalized X/Y pan controls
(-1 to +1; zero centers the crop). Python provides matching `--zoom`, `--pan-x`,
and `--pan-y` options. The selected preview uses the same 64×16 transform as its
send.

The optional still-image vertical loop first fits the image to 64 pixels wide,
preserving aspect ratio up to 4,096 rows, then creates adjacent 16-row viewports.
The foreground sender streams one viewport at a time with the native Scroll Up
transition at 250 ms/step and loops to the top. BLE stays connected only while
this explicit mode runs; Android/iOS expose cancellation, and Python stops with
Ctrl-C. Animated GIFs do not combine with this mode. Each update fits the live
one-frame receive limit rather than packaging the whole scroll as one animation.
Physical transition smoothness and loop-wrap behavior remain to be validated.
