# Dramatrix Plugins

This repository hosts the independent source and signed GitHub Releases for Dramatrix business plugins. Plugin ZIPs and catalogs are published only to this repository's GitHub Releases. The backend, desktop app, and marketplace runtime remain in the Dramatrix main repository.

## Source migration

Profile plugins are the first source family migrated here. They have no Java runtime dependency and can be built from this checkout without cloning Dramatrix. Media and management sources will migrate after their API and SDK dependencies are available.

## Build a Profile plugin

```bash
mvn --batch-mode --file pom.xml \
  --projects :profile-plugin-cyberpunk-2099 \
  clean package -Dplugin.deploy.skip=true

python3 release/plugin_package.py package \
  --project-root . \
  --module dramatrix-plugin/profile/profile-plugin-cyberpunk-2099 \
  --type profile \
  --output-dir release/build/plugin-packages \
  --unsigned
```

`--unsigned` is for local inspection and CI validation only. Official plugin releases require the protected `DRAMATRIX_PLUGIN_SIGNING_KEY_PEM` Actions secret and the non-secret `DRAMATRIX_PLUGIN_SIGNING_KEY_ID` repository variable. See the publishing procedure in [`release/README.md`](release/README.md).
