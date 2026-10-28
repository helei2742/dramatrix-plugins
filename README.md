# Dramatrix Plugins

This repository hosts the independent source and signed GitHub Releases for Dramatrix business plugins. Plugin ZIPs and catalogs are published only to this repository's GitHub Releases. The backend, desktop app, and marketplace runtime remain in the Dramatrix main repository.

## Source migration

Profile and media plugin sources are migrated here. Profile packages build from this checkout without host source; media packages use the independently versioned `dramatrix-plugin-api:1.0.0` artifact from GitHub Packages. Management sources remain in the main repository until their host dependency strategy is settled.

## Build plugin packages

The independent Maven reactor contains six Profile modules and 32 media modules. GitHub Actions builds only changed modules; media builds read the versioned plugin API from GitHub Packages. Grant this repository's Actions workflow read access to the `dramatrix-plugin-api` package in `helei2742/dramatrix`.

For a local unsigned build of all packages of one type:

```bash
./release/build-plugin-packages.sh --type profile --unsigned
./release/build-plugin-packages.sh --type media --unsigned
```

The Profile example below builds one module directly:

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

Build a media plugin by its source module path (replace the path as needed):

```bash
mvn --batch-mode --file dramatrix-plugin/comfyui/image/qwen-image2.1-text2image/pom.xml \
  clean package -Dplugin.deploy.skip=true
python3 release/plugin_package.py package \
  --project-root . \
  --module dramatrix-plugin/comfyui/image/qwen-image2.1-text2image \
  --type media \
  --output-dir release/build/plugin-packages \
  --unsigned
```

`--unsigned` is for local inspection and CI validation only. Official plugin releases require the protected `DRAMATRIX_PLUGIN_SIGNING_KEY_PEM` Actions secret and the non-secret `DRAMATRIX_PLUGIN_SIGNING_KEY_ID` repository variable. See the publishing procedure in [`release/README.md`](release/README.md).
