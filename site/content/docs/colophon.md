+++
title = "Colophon"
description = "The tools and visual system behind this field guide."
weight = 6
+++

**Presence** is a library by Transductive, released under the MIT license.

This site uses **Zola 0.23.6**, a Rust static-site generator, and **DevLab 0.9.0**. Markdown holds the guides; Tera templates assemble the pages. Java's standard Javadoc tool generates the API reference from the same source used to build the library.

## Theory meets the machine

The visual language follows Transductive's *Editorial System v0.1*: ink `#090908`, paper `#F8F6F1`, signal red `#DB2913`, precise rules, and quiet annotations. Bodoni Moda carries editorial headings, Anton provides condensed display type, and IBM Plex Mono carries labels and code. Long-form prose uses the system sans-serif for reading.

The fonts are served locally under the SIL Open Font License. DevLab and its included third-party assets retain their upstream license notices. Presence-specific overrides live outside the vendored theme.

- [Zola](https://www.getzola.org/)
- [DevLab theme](https://codeberg.org/RiPetitor/devlab-theme)
- [DevLab MIT license]({{ get_url(path="licenses/devlab-LICENSE.txt") }})
- [DevLab third-party notices]({{ get_url(path="licenses/devlab-THIRD_PARTY_NOTICES.md") }})
- [Bodoni Moda license]({{ get_url(path="fonts/bodonimoda-OFL.txt") }})
- [Anton license]({{ get_url(path="fonts/anton-OFL.txt") }})
- [IBM Plex Mono license]({{ get_url(path="fonts/ibmplexmono-OFL.txt") }})

The site has no analytics, external font requests, or hosted search service. Documentation search runs in your browser. API search is provided by Javadoc.
