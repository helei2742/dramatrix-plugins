# Dramatrix Plugins

This repository is the GitHub Releases destination for Dramatrix plugin ZIPs, signatures, catalogs and channel pointers. Plugin source of truth remains in [`helei2742/dramatrix`](https://github.com/helei2742/dramatrix).

The `publish-plugins.yml` workflow accepts a source commit SHA from the main repository, verifies it is reachable from `dramatrix/main`, then builds and signs that checkout. It publishes immutable version assets, reads them back, and activates the signed catalog only after verification succeeds.

The publisher secret `DRAMATRIX_PLUGIN_SIGNING_KEY_PEM` and variable `DRAMATRIX_PLUGIN_SIGNING_KEY_ID` belong in this repository's Actions settings. Do not commit private keys or publishing tokens here.
