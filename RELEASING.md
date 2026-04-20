# Releasing pikpak-kotlin

End-to-end workflow for cutting a new version onto Maven Central via GitHub Actions. The repo is wired so that **pushing a `v*.*.*` git tag** triggers `.github/workflows/release.yml`, which fans out across a `strategy.matrix` (one cell per shipped target, each on the cheapest runner that can handle it — ubuntu-latest for JVM/Linux/Windows-cross-compile/Android, macos-latest for Apple targets) and then publishes signed artifacts to the Sonatype Central Portal once every cell passes.

This document is one-time setup plus the per-release ritual.

---

## One-time setup

### 1. Sonatype Central Portal account + namespace

1. Sign up at <https://central.sonatype.com> with your GitHub identity (or email).
2. Open **Namespaces** → **Add Namespace** → enter `io.github.nihildigit`.
3. Verify by creating a public GitHub repo whose name is the verification token Sonatype gives you (e.g. `OSSRH-12345678`). It can be empty. Once Sonatype detects it, namespace status flips to *Verified* — usually within a minute. You can delete the verification repo afterwards.
4. Generate a **User Token** under your Central Portal profile. You'll get a `username` and a `password` string (these are *not* your login credentials — they're scoped tokens). Keep them; you'll paste them into GitHub secrets below.

### 2. GPG signing key

Maven Central rejects unsigned artifacts. Generate an ASCII-armored key once and reuse it.

```bash
# Generate (4096-bit RSA, valid 2 years; use no passphrase only if you're OK with it
# living unencrypted in GitHub secrets — recommended: do set one).
gpg --full-generate-key

# List, find the key ID (the long hex after "rsa4096/"):
gpg --list-secret-keys --keyid-format=long

# Export PRIVATE key in ASCII (this is what Gradle signs with):
gpg --armor --export-secret-keys YOUR_KEY_ID > pikpak-signing.asc

# Publish PUBLIC key to keyservers so Maven Central can verify your signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org      --send-keys YOUR_KEY_ID
```

### 3. GitHub repository secrets

Repo → Settings → Secrets and variables → Actions → **New repository secret**. Add four secrets:

| Secret name              | Value                                             |
| ------------------------ | ------------------------------------------------- |
| `MAVEN_CENTRAL_USERNAME` | The Central Portal user-token *username*          |
| `MAVEN_CENTRAL_PASSWORD` | The Central Portal user-token *password*          |
| `SIGNING_KEY`            | Full contents of `pikpak-signing.asc` (incl. header/footer) |
| `SIGNING_KEY_PASSWORD`   | The passphrase you set in step 2 (empty string if none)     |

Once these four are in place, **delete `pikpak-signing.asc` from your machine** (or move it to a password manager). The CI job won't need it again.

---

## Per-release ritual

```bash
# 1. Bump the version. Drop -SNAPSHOT for the actual release.
#    Edit build.gradle.kts: version = "0.2.0"

# 2. Commit the bump.
git add build.gradle.kts
git commit -m "release: 0.2.0"

# 3. Tag and push. The release workflow validates that tag == build.gradle.kts version.
git tag -a v0.2.0 -m "v0.2.0"
git push origin main v0.2.0

# 4. Watch the workflow at:
#    https://github.com/NihilDigit/pikpak-kotlin/actions
#    A successful run takes ~9 min total: test matrix fans out in parallel
#    (longest cell is iosSimulatorArm64Test ~4 min on macos-latest),
#    then publish runs ~4 min on macos-latest.

# 5. Bump back to a SNAPSHOT for ongoing dev.
#    Edit build.gradle.kts: version = "0.2.1-SNAPSHOT"
git add build.gradle.kts
git commit -m "back to dev: 0.2.1-SNAPSHOT"
git push
```

Because `mavenPublishing { publishToMavenCentral(..., automaticRelease = true) }` is set, artifacts go from staging straight to released — typically searchable on <https://search.maven.org> within ~30 minutes of the workflow finishing.

If `automaticRelease` is ever turned off, the workflow will leave artifacts in staging on the Central Portal UI; you'd then click *Publish* manually.

---

## Verifying a release

```kotlin
// In a consumer project's build.gradle.kts:
repositories { mavenCentral() }
dependencies {
    implementation("io.github.nihildigit:pikpak-kotlin:0.2.0")
}
```

Gradle picks the right per-target artifact (`-jvm`, `-iosarm64`, `-linuxx64`, ...) automatically based on the consumer's build target — no extra coordinates needed.

---

## Common failures

- **"Namespace not verified"** at upload time → step 1.2/1.3 didn't complete. Re-check the verification repo exists publicly.
- **"PGP signature verification failed"** → the public key isn't on a keyserver yet, or the wrong subkey was published. Re-run the `--send-keys` from step 2 against multiple keyservers; propagation can take several minutes.
- **Tag/version mismatch** workflow failure → fix the `version` in `build.gradle.kts`, force-update the tag (`git tag -f v0.2.0 && git push -f origin v0.2.0`), or just delete the tag and re-tag.
- **Apple-target compilation errors** from Linux contributors → expected; `kotlin.native.ignoreDisabledTargets=true` in `gradle.properties` would suppress the warning, but full Apple compilation only happens on the macOS release runner.
