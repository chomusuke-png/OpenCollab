# Publishing on the JetBrains Marketplace

How OpenCollab gets onto the [JetBrains Marketplace](https://plugins.jetbrains.com) and how each
new version follows. JetBrains reviews every new plugin and every update by hand; there is no
fixed review time (write to marketplace@jetbrains.com after 3–4 working days without an answer).

## The Marketplace build

Build with `-Pmarketplace` for the Marketplace:

```bash
./gradlew :plugin:buildPlugin -Pmarketplace
```

It is the same plugin, except that it gets its updates from the Marketplace: it never adds the
GitHub release feed to the IDE's plugin repositories, and it removes the feed again if an earlier
GitHub build added it. *Copy Diagnostics* names the build ("JetBrains Marketplace build" or
"GitHub build"). `publishPlugin` refuses to publish a build made without `-Pmarketplace`.

## Once: before the first upload

1. **Account:** sign in at <https://plugins.jetbrains.com> with a JetBrains Account.
2. **Developer agreement and vendor profile:** *Upload plugin* asks to accept the JetBrains
   Marketplace Developer Agreement and to create a vendor profile. Use the same vendor as the
   plugin: name *chomusuke-png & hi444*, email owokenji@gmail.com, website
   https://github.com/chomusuke-png. JetBrains requires both to be valid and working.
3. **Signing key (free):** without a signature the IDE warns when the plugin is installed.
   Generate a key and a self-signed certificate once, and keep them **out of the repository**
   (for example in a password manager):

   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   The build reads them from the environment (PowerShell shown; the same with `export` in a
   shell):

   ```powershell
   $env:OPENCOLLAB_CERTIFICATE_CHAIN = Get-Content -Raw chain.crt
   $env:OPENCOLLAB_PRIVATE_KEY = Get-Content -Raw private.pem
   $env:OPENCOLLAB_PRIVATE_KEY_PASSWORD = '<the password, if the key has one>'
   ```

## First upload (by hand)

1. Update the changelog and release on GitHub as usual (see the README), so the version has
   release notes.
2. Build and sign: `./gradlew :plugin:signPlugin -Pmarketplace`. The signed ZIP is
   `plugin/build/distributions/OpenCollab-<version>-signed.zip`.
3. On the Marketplace: *your profile → Upload plugin*. Choose the vendor, the signed ZIP and:
   * **License:** the end-user license agreement, [LICENSE.MD](../LICENSE.MD) (by link if the
     repository is public, otherwise paste its text where the form allows it).
   * **Privacy policy:** [docs/PRIVACY.md](PRIVACY.md). JetBrains requires one when personal data
     is handled; IP addresses are personal data.
   * **Category and tags:** for example *Collaboration*, *Remote*, *Pair programming*.
4. After approval, on the plugin's page: add screenshots (the tool window while hosting, a guest
   editing, the invitation dialog), a link to the user guide and the issue tracker or contact
   email.

## Every later version

1. Release on GitHub as usual.
2. Create a permanent token on the Marketplace (*your profile → My Tokens*) once and put it in
   `OPENCOLLAB_MARKETPLACE_TOKEN`, together with the signing variables above.
3. Publish: `./gradlew :plugin:publishPlugin -Pmarketplace`. It builds, signs and uploads; the
   update appears after JetBrains approves it.

## Before making the repository private

These depend on the repository being public, and stop working when it is private:

* The relay installers (`curl …/install.sh | sh`, `irm …/install.ps1 | iex`) download from it.
* The plugin's links to the user guide and to what's new point to files in it, and so do the
  links in the Marketplace description and this privacy policy.
* GitHub builds of the plugin get their updates from its releases. Their users would have to
  install the plugin once from the Marketplace.
* The relay image `ghcr.io/chomusuke-png/opencollab-relay` is linked to it: check that the package
  stays public in its package settings.

A small public repository (or GitHub Pages site) with the user guide, privacy policy, changelog
and the relay installer and compose files would keep all of these working.

## Marketplace rules this plugin follows

From the [approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html):
the name has no "Plugin", "IntelliJ" or "JetBrains"; the description is in English; the icon is an
original 40×40 SVG; the vendor email and website are set; the plugin has its own license and a
privacy policy; it sends no telemetry; the Plugin Verifier finds no internal API use; and it does
not change IDE settings on its own in the Marketplace build.
