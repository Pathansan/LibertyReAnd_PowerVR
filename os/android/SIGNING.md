# Android signing

The release key is **not** in this repository. `release.keystore` used to be
committed together with its password (`android`), which means that key must be
treated as compromised: anyone can build an APK that Android accepts as an
update to a previously installed build.

## Rotate the key once

```bash
keytool -genkeypair -v \
  -keystore ~/keys/liberty_release.keystore \
  -alias liberty_powervr \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<strong password>' -keypass '<strong password>' \
  -dname "CN=LibertyRecomp, O=LibertyRecomp, C=US"
```

Keep the file outside the working tree. Publishing a build signed with the
**new** key means existing installs cannot update over the old APK (Android
rejects a signature change), so the first release after rotation has to be
installed fresh.

## Purge the old key from history

The compromised keystore is still in every commit that predates its removal
from the index, so rotate first and then rewrite history once:

```bash
pip install git-filter-repo
git filter-repo --invert-paths --path os/android/app/release.keystore
git push --force-with-lease origin main
```

Everyone with a clone has to re-clone (or `git fetch && git reset --hard
origin/main`) afterwards. Treat the old key as public either way: rewriting
history does not un-leak it.

## Repository secrets

`.github/workflows/build-apk.yml` expects four secrets:

| Secret | Value |
| :--- | :--- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ~/keys/liberty_release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | `liberty_powervr` |
| `ANDROID_KEY_PASSWORD` | key password |

The workflow decodes the keystore into `$RUNNER_TEMP` with `umask 077` and
passes the passwords to `apksigner` as `env:` references, so they never appear
on a command line or in the logs.

## Local builds

`os/android/app/build.gradle.kts` reads the same values from
`gradle.properties` (or the environment under the same names):

```properties
libertyReleaseKeystore=/home/you/keys/liberty_release.keystore
libertyReleaseStorePassword=...
libertyReleaseKeyAlias=liberty_powervr
libertyReleaseKeyPassword=...
```

Without them, `assembleRelease` produces `app-release-unsigned.apk`. That is
enough for the staging step in `build-apk.yml` (which re-signs the patched APK),
but it is not installable on its own. The release build type never falls back
to the debug key.
