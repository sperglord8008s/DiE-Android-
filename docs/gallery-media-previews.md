# Bounded gallery media previews

Gallerize's bounded audio/video cards now show platform MIME, duration, source
video dimensions and rotation. Where supported, a small decoded video keyframe
appears alongside its original offset, extent and label. Card details retain a
source-identified, explicitly **transient platform observation**, separate from
the original PhotoRec finding. Full metadata remains available through
**MPV codecs / streams / tags**; the small preview metadata set does not replace
that engine or claim to expose every media property.

## Source and rendering contract

`MediaFrame` accepts an owned `InputSnapshot`, checked nonempty `FileRange` and
1–512 pixel target. It verifies the whole input SHA-256 before and after the
operation and uses only `MediaMetadataRetriever.setDataSource(fd, offset,
length)`. Filenames and paths are never used as media URLs. Unknown recovered
extents do not reach a decoder; users can still choose explicit inspection
bytes without changing the original finding.

On API 27+, `getScaledFrameAtTime` requests the closest sync frame to 0 µs with
both dimensions bounded by the target. Returned dimensions/pixels are checked;
there is no unbounded `getFrameAtTime` fallback. API 26 retains metadata and an
explicit scaled-frame limitation. A missing/failed frame retains available
metadata and its original error. Audio-only observations have no invented
image. Zero, changed, foreign-sized or cancelled source scopes are refused.

The exact interval, source digest/size, API, raw/null metadata, per-field errors,
frame request/selection and decoded dimensions are present in card details.
The actual frame timestamp remains null: asking for the closest sync frame is
not proof that it represents time zero. Metadata values remain strings, so
unknowns and large numeric values are preserved.

A gallery page still contains at most 24 cards, with one request per card and
256-pixel thumbnails. Work runs on its existing background executor. Closing or
changing a page stops future work and discards late bitmaps. Bitmaps already
referenced by views remain valid until collection. Platform native calls are
not cooperatively interruptible; checks occur around those calls. The returned
bitmap budget does not bound internal codec buffers, native execution time or
whole-process memory. This helper runs in the normal gallery application UID,
as the existing platform image/video/PDF previews do; it is not migrated into
the isolated hash/archive service.

These observations are transient presentation data, visible in card details;
they are not silently appended to persistent native captures or existing origin
exports. A thumbnail or metadata value does not establish full-file validity,
complete decoding, playback support or safety. Native evidence, exact-byte
exports and all existing tools/actions remain available.

## Verification

Five new API 28/26 host widget/guard cases pass: exact descriptor coordinates and
bounded frame requests; raw/null/large values and defensive observation copies;
codec-failure metadata retention/resource release; API 26 without unbounded
fallback; changed/cancelled/empty/foreign scopes and unknown-extent cards never
reaching a decoder. The platform codec is substituted in these host tests.

The separate Android runtime laboratory now generates owned solid-green MPEG-4
and PCM fixtures. The 991-byte owned clip is retained with its SHA-256 and
FFmpeg generation recipe; Python generates PCM. Ordinary builds need neither
FFmpeg nor network for these fixtures. It tests actual embedded-range metadata, video
geometry/duration, bounded green frame pixels on API 35, metadata-only API 26
behavior and audio-only absence of a frame. The complete **175-case host suite**, required generic/native checks and
ARM64 assembly/lint/fifteen-library APK verification pass; the final frame-budget
guard rerun also passes. **The new actual codec checks and five-job source CI
remain pending for this slice.** The original
five worker contracts already passed on both API levels at source `26ad7d6`;
that evidence does not substitute for executing the new preview code.

No production native dependency or source pin changes. The Android framework
supplies the preview implementation. Optional video regeneration uses FFmpeg;
it is not added to the production APK or required by ordinary runtime-lab builds. Existing separately
pinned MPV/FFmpeg investigation engines and notices remain unchanged.
