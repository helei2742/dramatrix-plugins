# Dramatrix Plugins

This repository is the GitHub Releases destination for Dramatrix plugin ZIPs, signatures, catalogs and channel pointers. Plugin source of truth remains in [`helei2742/dramatrix`](https://github.com/helei2742/dramatrix).

The `publish-plugins.yml` workflow accepts a source commit SHA from `dramatrix/dev_1.8`, verifies it is reachable from that branch, then builds and signs that checkout. It publishes immutable version assets, reads them back, and activates the signed catalog only after verification succeeds.

The publisher secrets `DRAMATRIX_PLUGIN_SIGNING_KEY_PEM` and `DRAMATRIX_SOURCE_READ_TOKEN`, and variable `DRAMATRIX_PLUGIN_SIGNING_KEY_ID`, belong in this repository's Actions settings. `DRAMATRIX_SOURCE_READ_TOKEN` must have Contents: read access to the private `helei2742/dramatrix` repository; limit it to that repository. The source checkout removes the token before any build scripts run. Do not commit private keys or publishing tokens here.
