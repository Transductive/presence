# Presence website

Zola 0.23.6 + DevLab 0.9.0, with Transductive's Theory Meets the Machine editorial system.

From the repository root:

```sh
mise install
mise run site:serve
```

Open http://127.0.0.1:8787/. This builds the complete site, including Javadoc.
Stop with Ctrl+C and restart after edits. To use another port:
`mise run site:serve -- -PsitePort=8788`.

`mise run site:check` builds, tests the playground compiler bridge, and checks
internal HTML links, fragments, and CSS assets under the deployment prefix.
`mise run site:build` assembles `build/site/`.
The default deployment base is `https://transductive.github.io/presence/`;
override it with `-- "-PsiteBaseUrl=https://example.com/docs/"`. Building does not
publish. The artifact can be served by any static host; no Node or application
server is needed. The Pages workflow builds and verifies it before deployment;
the separate library CI builds and runs unit tests on Windows, macOS, and Linux.

## Publish with GitHub Pages

The `Deploy documentation` workflow in `.github/workflows/pages.yml` builds and
deploys on pushes to `main`. It can also be run manually from the Actions tab on
`main`. Feature branches and pull requests are checked by CI but do not publish.

One-time repository setup:

1. Under **Settings → Pages → Build and deployment**, choose **GitHub Actions**.
2. Make sure the repository's visibility and GitHub plan support Pages. Public
   repositories can use Pages on GitHub Free; a private repository needs an
   eligible paid plan.
3. Merge the website and workflow into `main`, or manually run the workflow if
   they are already there.

The build reads the actual base URL from GitHub Pages, runs `mise run site:check`,
and uploads only `build/site/`. The deployment job runs after those checks pass,
using the `github-pages` environment and GitHub's built-in token. No personal
token or repository secret is required. Deployment write permissions are limited
to that job; build steps only read the repository and Pages configuration.

The workflow handles publishing separately from the cross-platform library CI.
The deploy job reports the live URL in Actions and the repository's deployments.
Generated HTML is an Actions artifact and remains outside Git.

## Browser playground

The homepage's **Open playground** link opens `/playground/`. Edit the Java 21
example and select **Run**, or press Ctrl/Command+Enter. A fresh Web Worker loads
CheerpJ 5.0, compiles `Playground.java`, and runs it against the actual Presence
JAR built with this site. **Stop** terminates the worker; **Reset example** stops
it and restores the example. Source and compiled classes stay in memory. There
is no compiler backend, account, or automatic source persistence.

The page fetches CheerpJ only after Run. Its first startup requires a network
connection and can take several seconds. Loading, compilation, and execution
have separate 120-second, 60-second, and 10-second limits. Source is limited to
32,768 characters, captured stdout/stderr to 32 KiB. Output appears when the
program finishes; stopped programs lose their buffered output. This is for small
console examples, not JMH or native-JVM performance comparisons. Browser memory
limits still apply; Web Workers do not provide a configurable heap quota.

`sitePlaygroundJar` builds the JDK-only bridge from `playground/java/`. It is
separate from the library JAR and dependency graph. `sitePlaygroundAssets`
assembles both JARs with content-hashed filenames and a generated manifest;
`siteBuild` includes them under `playground/lib/`. Nothing generated is checked
in. `site:check` runs the bridge's unit tests; the normal library `build`/CI still
runs only core tests, with no benchmarks or browser runtime downloads.

The Java preview server (`../tools/SiteServer.java`) adds JAR byte-range support
to the JDK static-file handler. GitHub Pages supports these requests too. Test
the project prefix locally with:

```sh
mise run site:serve -- "-PsiteBaseUrl=http://127.0.0.1:8787/presence/"
```

### CheerpJ licensing

Checked against the [vendor's licensing documentation](https://cheerpj.com/docs/licensing)
on 2026-11-12. Community use covers a non-commercial open-source project's web
demo, as well as individuals and one-person companies, with a **Built with
CheerpJ** credit and runtime loading from the vendor CDN. The playground includes
that credit. We do not bundle or self-host CheerpJ, and its license is separate
from Presence's MIT license. Company-backed projects selling support, hosting,
or paid editions should recheck the [application license terms](https://cheerpj.com/licensing/)
before deployment; the project being MIT alone does not establish eligibility.

The pinned loader is `https://cjrtnc.leaningtech.com/5.0/loader.js`, initialized
with Java 21. This uses the stable JavaScript/WebAssembly runtime; the
experimental WasmGC engine is not enabled.

## Source ownership

- `content/`: authored Markdown guides and the homepage code example.
- `templates/`: homepage and small DevLab overrides. These HTML files are source.
- `static/`: local styles, fonts, favicon, sizing calculator, and playground UI.
- `playground/`: website-only Java compiler bridge and its unit tests.
- `themes/devlab-theme/`: unmodified vendored runtime, MIT licensed.
- Java source comments: generated API reference; do not hand-edit generated HTML.
- `javadoc/`: API presentation overrides; assembly removes Javadoc's unused
  DejaVu font import because the site supplies its own local fonts.
- `../resources/bloomfilters.pdf`: the bundled theory reference.

The sizing calculator uses the classical approximation. It does not execute the
Java implementation or claim a measured heap footprint. Benchmark HTML and local
review manifests are never included automatically.

## Theme provenance and upgrades

Vendored runtime directories (`templates`, `sass`, `static`, `third_party`),
`theme.toml`, and license notices come from
https://codeberg.org/RiPetitor/devlab-theme at tag **v0.9.0**, commit
`2b638831313058ba92d17095fce15454fda02eba`. Demo content and upstream tests are
excluded. Use ordinary files so a fresh clone works without submodule setup.
Keep all Presence changes outside that directory. On an upgrade, replace the
runtime tree, preserve notices, review our small overrides, and rerun site checks
plus desktop/mobile search and navigation checks.

Fonts are original Google Fonts TTFs (Bodoni Moda variable, Anton Regular, IBM
Plex Mono Regular/Bold), with their SIL OFL notices beside them. The Figma system
source is file `WwZrc3AhM6w4AadlDkw2CO`, board `5:212` (Editorial System v0.1).
No temporary Figma URLs, remote fonts, analytics, or private benchmark results
are used at runtime.
