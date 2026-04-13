# Distinguishing User-Taken Photos from Others

Using the fields exposed on `MediaAsset`, here's a practical decision guide for both iOS and Android.

## Available signals

| Field | iOS | Android |
|---|---|---|
| `isScreenshot` | `PHAssetMediaSubtype.photoScreenshot` | path contains `Screenshots/` |
| `isCameraCapture` | user library + filename matches `IMG_####` | path contains `DCIM/` |
| `hasLocation` | `PHAsset.location != nil` | always `false` (not read from EXIF) |
| `hasAdjustments` | `PHAsset.hasAdjustments` | always `false` |
| `creationDate` | original capture date | `DATE_ADDED` (library-add time) |
| `modificationDate` | library-add / edit time | `DATE_MODIFIED` |
| `sourceType` | `userLibrary` / `cloudShared` / `itunesSynced` | `""` |
| `source` | `""` | `camera` / `screenshot` / `messaging:*` / `download` / `pictures` / `other` |
| `isFavorite` | `PHAsset.isFavorite` | always `false` |

Both `sourceType` and `source` are returned on both platforms — the one that doesn't apply is an empty string.

## Categories

| Category | Meaning |
|---|---|
| `screenshot` | Screen capture |
| `taken_by_me` | High-confidence own camera capture |
| `taken_by_me_likely` | Probably own capture, some ambiguity |
| `shared_with_me` | High-confidence shared/received (iMessage, WhatsApp, etc.) |
| `shared_with_me_likely` | Probably shared, some ambiguity |
| `downloaded_or_saved` | Web download, saved image, meme, sticker |
| `shared_album` | iOS shared album (not your library) |
| `synced` | iTunes/Finder-synced photo |
| `unknown` | Doesn't match any pattern |

## iOS classification

```ts
function classifyPhotoIOS(asset: MediaAsset) {
  if (asset.isScreenshot) return 'screenshot';

  // Non-user-library sources
  if (asset.sourceType === 'cloudShared') return 'shared_album';
  if (asset.sourceType === 'itunesSynced') return 'synced';

  // Not a camera capture → saved from web, messaging app, meme, etc.
  if (!asset.isCameraCapture) return 'downloaded_or_saved';

  // Primary signal: GPS presence.
  // - Own camera captures almost always have GPS (Camera location is on by default).
  // - iMessage/Mail strip GPS when sharing.
  // - AirDrop preserves GPS and is indistinguishable from own captures without
  //   EXIF Make/Model comparison (see getMediaDetails suggestion below).
  //
  // Note: modificationDate gap is NOT used because iCloud Photos sync, face
  // detection, Live Photo stabilization, etc. bump modificationDate on own
  // captures unpredictably, so any threshold either misclassifies own photos
  // or misses recently-shared ones.
  if (asset.hasLocation) return 'taken_by_me';
  return 'shared_with_me_likely';
}
```

## Android classification

Android is actually simpler because the storage path almost always reveals the origin.

```ts
function classifyPhotoAndroid(asset: MediaAsset) {
  if (asset.isScreenshot) return 'screenshot';

  // Messaging apps write to their own folders outside DCIM
  if (asset.source.startsWith('messaging:')) return 'shared_with_me';

  if (asset.source === 'download') return 'downloaded_or_saved';

  // DCIM/ → camera capture (stock or third-party camera apps)
  if (asset.source === 'camera') {
    // Optional refinement: check if added much later than taken.
    // On Android, creationDate currently reflects DATE_ADDED, so this is weaker than iOS.
    const creationMs = +new Date(asset.creationDate);
    const modMs = asset.modificationDate ? +new Date(asset.modificationDate) : creationMs;
    const gapMs = Math.abs(modMs - creationMs);
    return gapMs > 86_400_000 ? 'taken_by_me_likely' : 'taken_by_me';
    // (Android still uses the gap since path-based source='camera' is strong
    // and DATE_MODIFIED on Android isn't churned by iCloud-style background tasks.)
  }

  // Pictures/ (loose) — could be a saved screenshot, edited photo, copied file
  if (asset.source === 'pictures') return 'downloaded_or_saved';

  return 'unknown';
}
```

## Unified helper

```ts
import { Capacitor } from '@capacitor/core';

function classifyPhoto(asset: MediaAsset) {
  return Capacitor.getPlatform() === 'ios'
    ? classifyPhotoIOS(asset)
    : classifyPhotoAndroid(asset);
}

function isUserTaken(asset: MediaAsset) {
  const c = classifyPhoto(asset);
  return c === 'taken_by_me' || c === 'taken_by_me_likely';
}
```

## How the platforms compare

| Scenario | iOS result | Android result |
|---|---|---|
| Own photo, GPS on | `taken_by_me` | `taken_by_me` |
| Own photo, GPS off | `taken_by_me_likely` | `taken_by_me` |
| Screenshot | `screenshot` | `screenshot` |
| Saved from iMessage | `shared_with_me` | n/a — iMessage doesn't exist |
| WhatsApp received | `downloaded_or_saved`* | `shared_with_me` (via `source`) |
| AirDrop from friend (GPS on) | `shared_with_me_likely` | n/a |
| Web download | `downloaded_or_saved` | `downloaded_or_saved` |
| Shared album | `shared_album` | n/a |

\* iOS collapses all non-camera library additions into `downloaded_or_saved`, so it can't identify WhatsApp specifically. Android can because messaging apps write to named folders.

## Known limitations

1. **iOS can't identify origin app.** Anything saved to the library via "Save Image" from any app (Messages, Mail, Safari, WhatsApp, Instagram) looks identical at the PhotoKit layer. You only get "imported-like" signals (no GPS, import gap, no camera filename).
2. **AirDropped friend photos on iOS** preserve filename, EXIF, and GPS. If saved soon after capture they're indistinguishable from own captures without EXIF `Make`/`Model` comparison.
3. **Android `hasLocation` is always false** — EXIF GPS isn't read. The `source` field covers the common classification needs without it.
4. **Android `creationDate` is `DATE_ADDED`**, not the original capture time. This means the Android import-gap check is weaker than iOS. If you need it to be stronger, add `DATE_TAKEN` to the projection and use it instead of `DATE_ADDED`.
5. **Edited own photos on iOS**: `hasAdjustments` is used to suppress the import-gap signal so edits don't look like imports. Android has no equivalent.

## Raising accuracy further

Add a lazy `getMediaDetails(identifier)` method that reads EXIF per-asset and returns:

- `cameraMake`, `cameraModel`, `lensModel`
- `dateTimeOriginal` (true capture time, independent of library-add time)
- `hasExifLocation`
- A computed `takenByThisDevice` based on comparing `Model` against the current device

This is the only reliable way to:
- Distinguish AirDropped friend-photos from own captures on iOS
- Get real `hasLocation` on Android

Cost: ~5–20 ms per asset on Android (requires `ACCESS_MEDIA_LOCATION` permission and `MediaStore.setRequireOriginal()`); similar on iOS via `PHContentEditingInput`. Use on-demand, not inside the bulk `getMedias` call.
