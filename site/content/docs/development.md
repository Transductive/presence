+++
title = "Develop & contribute"
description = "One tool setup across Windows, macOS, and Linux."
weight = 5
+++

Mise owns the developer commands and tool versions. Gradle owns compilation, tests, packaging, and reproducible build outputs.

## Everyday commands

```sh
mise tasks ls
mise run build
mise run test
mise run docs
```

Use `mise where java` for the JDK installation directory. `mise exec -- java -version` runs the provisioned Java directly. Gradle arguments can be forwarded through mise:

```sh
mise run test -- --tests '*BloomFilterTest'
```

## Work on the website

```sh
mise run site:serve
```

Open **http://127.0.0.1:8787/**. This builds the guides, API reference, and bundled paper, then serves the assembled site. Stop with Ctrl+C. After editing, restart the command to rebuild; this preview does not live-reload.

```sh
mise run site:build
mise run site:check
```

The complete static artifact is `build/site/`. Content lives in `site/content/`, overrides in `site/templates/`, and styles and browser scripts in `site/static/`. Java source comments generate the API reference. Generated HTML stays out of Git; editable theme and site templates are tracked.

For another deployment address, pass `-PsiteBaseUrl=https://example.com/presence/` after `--`. The build preserves the path prefix in navigation and asset URLs.

## What belongs in core

Production code stays in `src/main/java` and uses the Java core library. Benchmarks have their own source set and dependencies. Website tools do not affect the library dependency graph.

Start a contribution with a concrete use case and a reproducible test. Changes to hashing need reference vectors and membership correctness checks; performance claims need measurements and their parameters.

## Roadmap

The fixed-capacity Bloom filter is available today. Counting filters, scalable filters, and serialization are proposed directions. The blocked layout is an experiment. These are not promises of released functionality.

[Explore the repository](https://github.com/Transductive/presence) or [open an issue](https://github.com/Transductive/presence/issues).
