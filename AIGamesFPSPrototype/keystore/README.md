# 🔐 keystore/

This folder holds the Android signing material for **com.aigames.fpsprototype**.

> ⚠️ **Prototype exception:** for zero-setup CI releases, the **throwaway**
> prototype keystore *is* force-committed here (password `aigames-proto`). This
> is acceptable ONLY because it is a disposable prototype key for an
> undistributed app. **Before any real distribution:** generate a fresh key,
> keep it out of git, and provide it to CI via the `AIGAMES_KEYSTORE_*` secrets
> (the release workflow prefers secrets over the committed key). Treat anything
> signed with the committed key as public.

## What lives here

| File | Tracked by git? | Purpose |
|------|-----------------|---------|
| `aigames-release.keystore` | ⚠️ committed (prototype only) | The (throwaway) private signing key |
| `keystore.properties`      | ⚠️ committed (prototype only) | Path + passwords consumed by the build |
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
