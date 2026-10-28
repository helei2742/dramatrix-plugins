# Plugin release tooling

`plugin_package.py` creates deterministic plugin ZIPs and Ed25519 signatures. `plugin_release.py` validates a release plan, uploads immutable GitHub Release assets, reads them back anonymously, signs the catalog, and activates it only after verification.

The configured official destination is this repository's GitHub Releases. `plugin-destinations.json` contains only the repository identity; signing material belongs in repository Actions secrets.

For local inspection, use `release/build-plugin-packages.sh --type profile --unsigned` or `--type media --unsigned`. Media builds resolve the stable `io.dramatrix:dramatrix-plugin-api:1.0.0` artifact from GitHub Packages; the repository's Actions workflow requires read access to that package. Official publication must use the protected `DRAMATRIX_PLUGIN_SIGNING_KEY_PEM` secret and `DRAMATRIX_PLUGIN_SIGNING_KEY_ID` variable; never create an unsigned official release.
