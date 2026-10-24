# Packaging & releases

## Automatic releases (GitHub Actions)

`.github/workflows/android.yml` runs on every push to `main`:

1. Unit tests (`testDebugUnitTest`)
2. Release build (`assembleRelease`, minified)
3. A permission check on the final APK. It fails if INTERNET, network-state, camera, location or storage permissions are present.
4. Publishes `tel-1.0.<run>.apk` as a GitHub Release
5. In parallel: device tests on an Android 11 emulator (database, v1→v2 migration, backup/restore, onboarding → two fill-ups → odometer check → switch to US units)

The version code is the workflow run number, so each release installs over the previous one.

## Signing

By default, releases are signed with the **shared key in `keystore/debug.keystore`** (password `android`). This keeps CI builds installable and lets each new release update the previous one. Because that key is public, anyone could sign an APK that looks like an update. For a personal sideloaded app this is usually acceptable. For wider distribution, use your own key.

### Use your own key

```bash
keytool -genkeypair -v -keystore tel-release.jks -alias tel -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tel-release.jks > tel-release.jks.b64
```

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `TEL_KEYSTORE_B64` | contents of `tel-release.jks.b64` |
| `TEL_KEYSTORE_PASSWORD` | keystore password |
| `TEL_KEY_ALIAS` | `tel` |
| `TEL_KEY_PASSWORD` | key password |

Then add this step before "Build release APK" in the workflow:

```yaml
      - name: Use release key
        if: env.TEL_KEYSTORE_B64 != ''
        env:
          TEL_KEYSTORE_B64: ${{ secrets.TEL_KEYSTORE_B64 }}
        run: |
          echo "$TEL_KEYSTORE_B64" | base64 -d > $RUNNER_TEMP/tel.jks
          echo "TEL_KEYSTORE=$RUNNER_TEMP/tel.jks" >> $GITHUB_ENV
          echo "TEL_KEYSTORE_PASSWORD=${{ secrets.TEL_KEYSTORE_PASSWORD }}" >> $GITHUB_ENV
          echo "TEL_KEY_ALIAS=${{ secrets.TEL_KEY_ALIAS }}" >> $GITHUB_ENV
          echo "TEL_KEY_PASSWORD=${{ secrets.TEL_KEY_PASSWORD }}" >> $GITHUB_ENV
```

Note: Android won't install an update signed with a different key. Switching keys means uninstalling first. Back up your data in the app before uninstalling, then restore it afterwards.

## Local release build

```bash
export TEL_KEYSTORE=/path/to/tel-release.jks TEL_KEYSTORE_PASSWORD=… TEL_KEY_ALIAS=tel TEL_KEY_PASSWORD=…
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

For Google Play, build an App Bundle with `./gradlew bundleRelease`.

## Renaming / rebranding

- Display name: `app/src/main/res/values/strings.xml` (`app_name`)
- Icon: `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_bg` in `values/colors.xml`
- Colours: `ui/theme/Theme.kt`
- Application id: `applicationId` in `app/build.gradle.kts`. Changing it creates a separate app.
