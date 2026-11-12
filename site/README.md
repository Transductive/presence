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

`mise run site:check` builds and checks internal HTML links, fragments, and CSS
assets under the deployment prefix. `mise run site:build` assembles `build/site/`.
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

## Source ownership

- `content/`: authored Markdown guides and the homepage code example.
- `templates/`: homepage and small DevLab overrides. These HTML files are source.
- `static/`: local styles, fonts, favicon, and the sizing calculator.
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
