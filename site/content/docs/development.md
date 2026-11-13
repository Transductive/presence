+++
title = "Develop & Contribute"
description = "One tool setup across Windows, macOS, and Linux."
weight = 6
+++

Mise owns the developer commands and tool versions. Gradle owns compilation, tests, packaging, and reproducible build outputs.

## Everyday Commands

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

## Check CI Locally

```sh
mise run ci                # Clean Gradle build on your host
mise run ci:lint           # Validate GitHub Actions workflows
mise run ci:local -- --list # List the selected job without running it
mise run ci:local          # Run the Ubuntu job in Docker
```

Mise installs pinned actionlint and act versions only when their tasks run.
For a container run, install and start [Docker](https://nektosact.com/installation/index.html#docker)
and check that `docker info` succeeds. Windows must use Linux containers.
The first run downloads the runner image, actions, and build tools. Docker is
optional for everyday library development and workflow linting.
Unlike `--list`, act's `--dryrun` also needs a Docker connection.

The local runner lints first, then runs the Ubuntu matrix entry of the build
workflow against your working tree, compiling and packaging the library and
running its unit tests. Benchmark compilation, fixture tests, and timed runs
are excluded from regular builds and CI; `mise run benchmark:check` runs the
fixture checks manually. Website verification runs in the separate Pages workflow.
Its Ubuntu 26.04 amd64 image uses emulation on Apple Silicon. GitHub still checks
the Windows and macOS jobs and performs Pages deployment. Local containers are
an approximation of hosted runners; act does not reproduce GitHub's permissions,
OIDC, or deployment environments. See the [act documentation](https://nektosact.com/).

The build does not need repository secrets. For GitHub API rate limits, use
`mise run ci:local -- --secret GITHUB_TOKEN` and enter a token at the prompt.
Optional root `.secrets`, `.env`, and `.vars` files stay out of Git.

## Work on the Website

```sh
mise run site:serve
```

Open **http://127.0.0.1:8787/**. This builds the guides, API reference, playground, and bundled papers, then serves the assembled site. Stop with Ctrl+C. After editing, restart the command to rebuild; this preview does not live-reload.

```sh
mise run site:build
mise run site:check
```

The complete static artifact is `build/site/`. Content lives in `site/content/`, overrides in `site/templates/`, and styles and browser scripts in `site/static/`. Java source comments generate the API reference. Generated HTML stays out of Git; editable theme and site templates are tracked.

For another deployment address, pass `-PsiteBaseUrl=https://example.com/presence/` after `--`. The build preserves the path prefix in navigation and asset URLs.

## What Belongs in Core

Production code stays in `src/main/java` and uses the Java core library. Benchmarks have their own source set and dependencies. Website tools do not affect the library dependency graph.

Start a contribution with a concrete use case and a reproducible test. Changes to hashing need reference vectors and membership correctness checks; performance claims need measurements and their parameters.

## Roadmap

Fixed-size and scalable Bloom filters are available in the source snapshot. Counting filters and serialization are proposed directions. The blocked layout remains a benchmark-only experiment. Proposed features are not promises of released functionality.

[Explore the repository](https://github.com/Transductive/presence) or [open an issue](https://github.com/Transductive/presence/issues).
