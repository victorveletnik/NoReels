An Android app that opens the Instagram web version inside a WebView and keeps
only two features: **Stories** and **Direct Messages**.

## What it does

*Whitelist instead of blacklist:*

Blocking known bad paths (`/explore/`, `/reels/`) is fragile, because profiles
live at `/<username>/` and cannot be listed. The app instead **allows only**:
`/`, `/direct/*`, `/stories/*` and `/accounts/*`. Everything else is redirected
to the inbox. Before login nothing is blocked, so the login, 2FA and password-reset flows
work normally.


## Build

- **Android Studio:** open the project and run `Build > Build APK(s)`.
- **GitHub Actions:** run the **Build APK** workflow. The APK is attached as a
  build artifact.
  
## Known limitations

- No push notifications (WebView cannot receive web push). Polling or a
  foreground service would be needed.
- Instagram can change its markup at any time, so hiding rules may need
  updates.
