# DriveBrowser

A native **Android Auto WebView browser** for car head units — for passengers and parked use only.
Package: `com.myapp.drivebrowser` · minSdk 35 (Android 15+) · Kotlin + View system.

> ⚠️ **Safety:** Never interact with this app while driving. It is intended for passengers or
> while the vehicle is safely parked.

## Architecture

A single `MainActivity` coordinates focused, modular components:

| Package | Responsibility |
|---|---|
| `web/ConfiguredWebView` | Central WebView setup, security policy, cleartext gating, UA profiles, desktop mode |
| `web/SslErrorHandlerHelper` | Per-(host + certificate) SSL error consent prompt |
| `tabs/TabManager` + `BrowserTab` | Multi-tab lifecycle, session save/restore |
| `data/BrowserPreferences` | All persisted settings, bookmarks, per-site permissions, tab session |
| `ui/adapters/*` | Tabs, bookmarks, and start-page grid RecyclerViews |
| `ui/ThemeManager` | Light / AMOLED-dark / system theme |
| `permissions/PermissionManager` | Runtime permission state (mic, location, notifications) |
| `settings/SettingsActivity` | Settings screen |
| `model/*` | `AppThemeMode`, `UserAgentProfile` |

## Implemented features

- ✅ Android Auto navigation-category launcher integration
- ✅ WebView browsing with URL/search bar, back / forward / reload / home
- ✅ Multiple real tabs + tab manager, with tab-count badge
- ✅ Restore-last-session and home-page-on-launch options
- ✅ Bookmarks (add/remove, list panel) + start-page quick-link grid
- ✅ Light + true-black AMOLED dark themes, plus "darken web pages" (algorithmic darkening)
- ✅ Desktop / mobile mode toggle with Chrome/Safari UA profiles
- ✅ Fullscreen video (e.g. DRM L3 playback handled by WebView)
- ✅ Microphone (getUserMedia) and geolocation permission flows with per-site consent
- ✅ **Voice / Web Speech API** (`webkitSpeechRecognition`) bridge to native `SpeechRecognizer`,
  origin-validated and gated by the per-site microphone consent flow
- ✅ **Ad & tracker blocking** — host-based blocklist applied to all sub-resource requests
  (`shouldInterceptRequest`), with a Settings toggle; bundled starter list, subdomain-aware
- ✅ Settings: home page, theme, restore tabs, desktop default, persistent URL bar, clear data

### Security posture (hardened vs. typical WebView defaults)

- `MIXED_CONTENT_COMPATIBILITY_MODE` — blocks active mixed content (scripts) on HTTPS pages
- Network config trusts the **system CA store only** (user-installed CAs are not trusted)
- Cleartext (HTTP) is gated behind an explicit per-host consent prompt
- SSL error bypass is keyed to the exact certificate, so a changed/MITM cert re-prompts
- Third-party cookies off by default; remote debugging disabled; `allowFileAccess = false`

## Not yet implemented (good next steps)

- Cached site icons / favicons on start-page cards
- QR code utilities (zxing dependency is wired in but unused)
- Configurable floating quick-action button, global display scale
- Share-to-bookmark intent target

## Build

1. Open the project in **Android Studio** (Giraffe+ with AGP 9.1 support) or build from CLI.
2. Create a `local.properties` in the project root pointing to your SDK:
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```
3. Build / install:
   ```
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

Requires Android SDK 37 and JDK 21.

### Enabling on Android Auto

Android Auto only shows non-approved apps after enabling **Developer settings → Unknown sources**
in the Android Auto app (tap the Version entry 10× to unlock developer mode).
