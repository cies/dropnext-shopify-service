# The install page looks like DropNext

Depends on: `010` for the content it styles; the two can be built side by side, as this one touches only the document's frame and classes.
Repos: the DSS only. The logo is a copy of the monolith's.


## Why

The install page is an unstyled document today: the browser's default serif font, no `charset` or `viewport` meta
(so it renders tiny on a phone, where a merchant is likely to approve the app from the Shopify app), and colors as inline
`style = "color:green"`. It is the one page of the DSS a merchant sees, right after the DropNext portal or one of our e-mails sent them there,
so it should look like it comes from the same company: the DropNext logo, the portal's font, and the e-mails' frame and palette.


## Behavioral contract

- **The frame is the e-mail's.** One centered white card, at most 600 px wide and fluid below that, on a light gray page,
  with 32 px of padding (24 px on a phone), as `emailLayout.kt` in the monolith lays out every e-mail.
  The page is light only (`<meta name="color-scheme" content="light">`), as the e-mails are.
- **The DropNext logo** opens the card, 160 px wide, as an `<img>` with the alt text "DropNext".
  It is a copy of the monolith's `src/resources/static/images/logos/dropnext-logo.svg`, checked in at `src/resources/dropnext-logo.svg`,
  read from the classpath once and embedded as a `data:image/svg+xml;base64,…` URI:
  the DSS serves no static files, and an inline `<svg>` would need `unsafe` raw HTML, which the presentation layer has never written.
  Its own fills stay as they are (gray "drop", violet "next"), which is the portal's light mode.
- **The portal's font**: Inter, loaded from Google Fonts with the URL the portal's layout uses
  (`https://fonts.googleapis.com/css2?family=Inter:wght@400..700&display=swap`, with the two `preconnect` links before it),
  falling back to the e-mails' system stack (`-apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif`),
  so the page still reads right when the font is blocked or slow.
- **One `<style>` element** in the `<head>` holds every rule, on a handful of classes; no inline `style` attribute is left,
  including the ones the report's `render*` functions write today, which become classes (`status-ok`, `status-warn`, `status-bad`).
  No Tailwind, no build step, no external stylesheet but the font's.
- **The palette** is the e-mails' (`EmailStyle` in the monolith's `presentation/htmlemail/helpers.kt`),
  with the status colors taken from the portal's `app.css`:

  | Token | Value | Source | Used for |
  |---|---|---|---|
  | page background | `#f4f4f5` | `EmailStyle.PAGE_BACKGROUND` | behind the card |
  | card background | `#ffffff` | `EmailStyle.CARD_BACKGROUND` | the card |
  | text | `#1a1a1a` | `EmailStyle.TEXT` | body copy |
  | heading | `#333333` | `EmailStyle.HEADING` | the `<h1>` |
  | muted | `#666666` | `EmailStyle.MUTED` | the details' contents, the reference line |
  | faint | `#999999` | `EmailStyle.FAINT` | the footer |
  | rule | `#e0e0e0` | `EmailStyle.RULE` | the footer's top border, the details' tables |
  | accent | `#755ff8` | `EmailStyle.ACCENT` (the portal's `--color-violet-600`, the logo's "next") | the button, links |
  | on accent | `#ffffff` | `EmailStyle.ON_ACCENT` | the button's text |
  | ok | `#15773a` | `--color-green-800` | "granted", "active", "added" |
  | warn | `#816316` on `#fff2c9` | `--color-yellow-800` on `--color-yellow-50` | the gaps block, "missing", "mismatched" |
  | bad | `#c52727` on `#ffe8e8` | `--color-red-800` on `--color-red-50` | the not-saved and unknown-store block, "failed" |

- **Type sizes** follow the e-mails: the heading 22/30 px at weight 600, body copy 16/24 px, small text (the details, the footer) 13/20 px.
- **The button** is the e-mail's: the accent as background, 12 × 24 px of padding, a 6 px radius, weight 600, no underline.
- **The footer** is the e-mail's sender line, centered under a rule: "DropNext B.V. · support@dropnext.com",
  the address a `mailto:` link.
- **The document is complete**: `<html lang="en">`, `<meta charset="utf-8">`, `<meta name="viewport" content="width=device-width, initial-scale=1">`,
  the `noindex` robots meta it has today, and the `<title>` of `010`.
- **The details stay readable on a phone**: the report's tables scroll sideways inside the card rather than widening the page,
  and long values (subscription URLs, ids) break anywhere.


## What changes

- `src/resources/dropnext-logo.svg`: the copy of the monolith's logo, byte for byte.
- `presentation/renderOAuthInstallPage.kt`: the document's `<head>` (metas, title, font links, the `<style>` element),
  the frame (page, card, logo, footer), and classes in place of every inline `style`.
  The style sheet is a constant in the same file; the logo's data URI is computed once, lazily.
- `CLAUDE.md`, "OAuth install flow": that the logo is a copy of the monolith's, to be replaced together with it.
  The monolith's `CLAUDE.md` gets the same line, next to its logo, in the same work session.


## Edge cases

- **The font does not load** (blocked, offline, a strict network): the system stack takes over, and nothing shifts but the glyphs.
- **The logo resource is missing from the classpath**: that is a build mistake, not a runtime state, so the page fails the same way
  any missing resource would, and the test below catches it first.
- **The monolith's logo changes**: the copy here does not follow by itself; both repos' `CLAUDE.md` say the two change together.
- **A very narrow screen** (320 px): the card loses its side margins and keeps 24 px of padding; the button wraps its text rather than overflowing.


## Tests to write

- `RenderOAuthInstallPageTest`:
  - the document has `lang`, `charset`, `viewport` and `color-scheme` metas, and the Inter stylesheet link;
  - the logo is an `<img>` with alt text "DropNext" and a `data:image/svg+xml;base64,` source that decodes to the resource's bytes;
  - no element carries a `style` attribute (the style sheet holds every rule);
  - the status words carry their class (`status-ok` on "granted", `status-bad` on "failed").
- A test that the logo resource exists and is an SVG (its first element is `<svg`), so a lost file fails the build.


## Decisions

1. **The accent is `#755ff8`**, the logo's and the portal's violet. The monolith's e-mails and bills already switched to it
   (`EmailStyle.ACCENT`, `BillPdfStyle.ACCENT`), so the page, the e-mails and the bills share one accent.
2. **The logo is a checked-in copy**, with a line in both repos' `CLAUDE.md` that the two change together.
3. **Inter comes from Google Fonts**, as in the portal, with the e-mails' system stack as the fallback.
