# 🔐 keystore/

This folder holds the Android signing material for **com.aigames.fpsprototype**.

> 🚨 **The prototype keystore that was briefly committed here has been removed.**
> While it was committed the repo was public, and it still exists in git
> **history**, so treat that key as **compromised**: generate a fresh one and
> **never commit it**. Builds get the key from the `AIGAMES_KEYSTORE_*` CI
> secrets, or from a locally generated, git-ignored keystore (see below).

## What lives here

| File | Tracked by git? | Purpose |
|------|-----------------|---------|
| `aigames-release.keystore` | ❌ **never** | The actual private signing key |
| `keystore.properties`      | ❌ **never** | Path + passwords consumed by the build |
| `keystore.properties.example` | ✅ | Template / documentation |
| `README.md` (this file)    | ✅ | Explains the rules |

## The golden rule of seamless updates

Android installs a new APK **on top of** an existing one (no uninstall) only if:

1. the **applicationId is identical** — fixed to `com.aigames.fpsprototype`,
2. the APK is **signed with the same key** — i.e. *this* keystore, and
3. the **`versionCode` is strictly higher** than the installed one.

`scripts/generate-keystore.sh` creates the key **once** and refuses to
overwrite it. **Back it up** to a secure, private location immediately — losing
it permanently breaks seamless updates for every existing install.

## CI / multiple machines

Do **not** commit the key. Instead store it (base64) in your CI secret store
and expose these environment variables to the build (they take precedence over
`keystore.properties`):

```
AIGAMES_KEYSTORE_PATH       # absolute path to the restored .keystore
AIGAMES_KEYSTORE_PASSWORD
AIGAMES_KEY_ALIAS
AIGAMES_KEY_PASSWORD
```
