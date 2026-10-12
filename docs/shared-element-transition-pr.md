# Shared-element ("one-shot") transition for collection / album cards → detail

## What this does

When you tap a playlist / album card on **Home (Discovery)**, **Search**, or **Library**, its
cover artwork now morphs continuously from the card position into the detail screen's hero
image — and morphs back on return. This is the "一镜到底" (one-shot) shared-element
transition implemented with Jetpack Compose `SharedTransitionLayout` + `sharedElement`.

### Baseline (what upstream already had)

Worth stating precisely, because the baseline is *not* "detail pages already morph":

- `sharedElement` in upstream is used for the **mini-player → Now Playing** artwork morph
  (`MeloXIOSNowPlayingScene.kt`, `sharedArtworkKey()`, `MeloXPlayerLinearBoundsTransform`), and
  for sharing **inside** a detail screen's own `SharedTransitionLayout`. It is **not** a
  card → detail cross-screen morph.
- On the **Home (Discovery)** screen upstream has **zero** card-side `sharedElement`
  (`grep -c sharedElement` = 0). Its `SharedTransitionLayout` there only wraps an
  `AnimatedContent` so a playlist page opens **over** the home page and back returns to the
  layer underneath — i.e. a **pop/push page transition**, not a shared-element morph.
- On the **Search** screen upstream has **no** shared-element code at all.

So there was no existing card → hero morph to extend: on Home the card side never participated,
and on Search nothing existed. This change wires the entry-page scope + a matched artwork key on
all three screens so the cover actually flies between the card and the detail hero.

## Where it lives

- `ui/discovery/MeloXDiscoveryScreens.kt` — the card side gains `meloXDiscoverySharedArtwork`
  + `artworkSharedKey` (upstream had no card-side `sharedElement` here at all), and the detail
  hero receives the host scope so the cover pairs across screens.
- `ui/search/SearchScreen.kt` — nothing existed upstream; `SearchScreen` wraps in a top-level
  `SharedTransitionLayout` and every result-card composable (`SearchDiscovery`,
  `ProviderSearchDiscovery`, `ProviderSearchSongResults`, `SearchSwipeSongRow`,
  `SearchCategoryPage`, `SearchCollectionDetail`, …) gains a `sharedTransitionScope` parameter
  and routes its cover through the new `meloXSearchSharedArtwork` modifier.
- `ui/library/LibraryScreen.kt` — upstream shared only **inside** the detail page; the card side
  is now paired too, and `MeloXStandardPlaylistHero` overrides its shared-element key and moves
  the drop shadow **out** of the `sharedElement` chain.

## Key design decisions

1. **Entry-page-provided scope (not self-contained).** The morph only works if the card and
   the hero share one `SharedTransitionLayout`. So the scope is created at the list level and
   threaded into the detail hero, instead of the hero building its own.
2. **Shadow stays outside the shared-element chain.** The hero's elevation/ambient shadow was
   originally drawn *after* `sharedElement`, which paints it into the transition overlay and
   leaves a black halo around the artwork on return (very visible on light themes). The shadow
   now lives on an outer `Box`; the shared element is the image alone, so static appearance is
   unchanged but the morph is clean.
3. **`renderInOverlayDuringTransition = true` on search cards.** Without it the artwork gets
   clipped by the list / detail clipping during the flight; keeping it in the shared overlay
   layer avoids that.
4. **Key prefixing to prevent collisions.** Keys are namespaced
   (`search-collection-artwork-<id>`, `discovery-collection-artwork-<id>`, and a
   `collection-` prefix for library). This matters because the same playlist can appear in two
   blocks on one Home page — a bare `<id>` key would pair the wrong card.
5. **Backward compatible.** Every new `sharedTransitionScope` parameter defaults to `null`;
   when it's `null` the detail screen falls back to its original self-contained
   `SharedTransitionLayout` + fade. Existing call sites that don't pass a scope are unchanged.
6. **Concurrent-transition safety (serialised + queued).** Rapid re-taps — *return, then
   immediately tap another card* — used to strand the previous card in the middle of the
   overlay: the single `AnimatedVisibility` cancels the outgoing transition and jumps straight
   to the new target, while the old card's `sharedElement` is still owned by the leaving hero,
   so it freezes mid-morph.

   Fixed with two coordinated states in the entry page:

   - **`occupiedCardKey` (stable occupancy key).** The card's `visible = destinationKey != selectedKey`
     must *not* flip back the instant the detail starts leaving — a transient `selectedDetail?.key`
     going `null` makes the just-collapsed card "revive" into `enter` while its `sharedElement`
     is still held by the exiting hero. The key therefore keeps pointing at the old destination
     until the exit transition has fully finished.
   - **`overlayTransitionBusy` (serialisation + queue).** While a transition is running, a new
     `onCollection` / `openDetail` request is **queued** (not dropped, not executed inline) and
     replayed as soon as the current transition settles. Dropping it loses input; executing it
     inline tears the old transition apart. The queue is consumed inside the *same* effect that
     owns the lock, so there is no "unlock → relock" window between two effects.

   Applies to both `SearchScreen` and the two Home/Explore data screens in
   `MeloXDiscoveryScreens.kt`. The settle margin is `PageEnter/ExitMillis + 60ms` so the
   shared-element overlay layer is fully drained before the card is released.

## Testing / demo

Recorded on a real device (vivo iQOO 15, Android 17, density 4) — the attached clip
(`shared-element-demo.mp4`, 1080×2376, 9.3 s) shows the sequence on the **Search** page:

1. search results list, tap a collection card;
2. the cover morphs continuously from the card into the detail hero (mid-flight frames show the
   artwork scale/position interpolating, not a cut);
3. detail settles, then back — the cover morphs back into its original card slot.

Verified frame-by-frame by extracting stills with ffmpeg: the artwork is present and interpolating
on every frame between the two end states, i.e. the shared element is genuinely continuous rather
than a fade between two copies.

## Scope note

This PR is intentionally limited to the shared-element transition. Other local work in the same
files (search/explore header collapse, bottom-bar spring tuning, recognition entry, tone
sampling) is **excluded** so the change stays reviewable. The shared-element wiring is fully
separable from those and compiles against the existing `MeloXMotion` primitives.

## API surface

- Uses `androidx.compose.animation.ExperimentalSharedTransitionApi` — opt-in at the call sites
  that declare the `SharedTransitionLayout` / `sharedElement` modifiers.
