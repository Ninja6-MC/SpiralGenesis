# Icon assets

Everything here except `icon-master.svg` and this file is **generated**. To change the
mark, edit `icon-master.svg` and re-run:

```bash
node scripts/export-icons.mjs
```

That script owns the palette, the plates, and the export ladder; the master owns the
geometry and nothing else. It refuses to write anything if the artwork breaches the
safe zone, so a clipped icon cannot ship by accident.

The rules these files implement live in `../../../brand/ICON_PLAN.md` (sibling repo).

**This directory is hand-maintained; `assets/` at the repository root is not.** The two
have opposite rules and similar names. `assets/` holds machine-synced copies of the
organisation marks, delivered by pull request from `.github/workflows/sync-assets.yml` in
`brand`; its master is `src/ninja6-master.svg` there, and a hand edit here survives only
until the next sync. This plugin's own artwork lives in this directory and is generated
from `icon-master.svg` beside it. See `N6-REPO-03` in the org standards register.

## What is here

| File | Variant | Used for |
|---|---|---|
| `icon-master.svg` | source | **The only file to edit.** Geometry only, no colour. |
| `icon.svg`, `icon-{64..1024}.png` | 1, opaque | Platform avatars. Dark plate `#12161A`. |
| `icon-transparent-dark.*` | 2 | Dark backgrounds. Mint `#00E5A3` / amber `#FFAC1C`. |
| `icon-transparent-light.*` | 3 | Light backgrounds. `#008F66` / `#B8730A`. |
| `icon-small-dark.*`, `icon-small-light.*` | 4 | **32px and below.** Simplified cut; exported at 16 and 32. |
| `icon-mono-dark.svg`, `icon-mono-light.svg` | 5 | Single colour, dots dropped. Stamps, print. |

Two things that are easy to get wrong:

- **At 32px and below, use the `icon-small-*` cut, not the full mark.** The full
  spiral at 16px is a 0.75px stroke with 1.12px gaps; it renders as noise. The full
  mark technically still holds at 32, but 32 is favicon territory and the simplified
  cut is the better read there, so both are exported and the small cut wins ties.
- **There is no single transparent variant.** Mint on `#F2F4F7` is 1.50:1, i.e.
  invisible. Pick the cut that matches the background, or use `<picture>` with
  `prefers-color-scheme` as the README does.

## Re-uploading to the platforms

Platform icons are **not** part of the release pipeline and do not update themselves.
Neither the `hangar-publish-plugin` Gradle config nor the `mc-publish` step in
`.github/workflows/release.yml` has an icon input, so this is a manual step whenever
the mark changes.

### Modrinth — 512x512

Web UI: project **Settings** -> **Icon** -> upload `icon-512.png`.

Or via the API, with a token carrying the `PROJECT_WRITE` scope:

```bash
curl -X PATCH "https://api.modrinth.com/v2/project/spiralgenesis/icon?ext=png" -H "Authorization: $MODRINTH_TOKEN" -H "Content-Type: image/png" --data-binary @docs/assets/icon-512.png
```

Expect `204 No Content`. The icon must be under 256 KiB; `icon-512.png` is about 10 KB.

### Hangar — 256x256

Web UI: <https://hangar.papermc.io/Ninja6-MC/SpiralGenesis/settings> -> project icon ->
upload `icon-256.png`.

Use the web UI here. Hangar's only icon route is
`POST /api/internal/projects/project/{slugOrId}/saveIcon` (multipart, field
`projectIcon`), and `/api/internal/` is not the stable public API -- it authenticates
with a browser session rather than a `HANGAR_API_TOKEN`, so it is not scriptable in the
way the Modrinth call above is.

### Others

`brand/ICON_PLAN.md` section 4 lists the remaining targets and their sizes -- GitHub org
avatar, Discord, CurseForge, SpigotMC. Re-verify the required size at upload time;
those platforms change requirements without announcement.
