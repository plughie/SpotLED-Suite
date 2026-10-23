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
