#!/usr/bin/env node
// Regenerates every SpiralGenesis icon variant from docs/assets/icon-master.svg.
//
//   node scripts/export-icons.mjs
//
// The master holds geometry only. This script is where colour and plates live, so
// the two never duplicate: one geometry, one variant table, N outputs. See
// brand/ICON_PLAN.md sections 3 and 5.
//
// Rasterizer is @resvg/resvg-js, pinned below. It is fetched into build/ on first
// run (gitignored) rather than committed as a package.json dependency - this is a
// Gradle repo and the icon export is the only thing here that wants Node.
//
// resvg has no equivalent of "rsvg-convert --id", so variants cannot be selected
// out of the master at render time. Instead each variant is composed into a
// standalone SVG here and rasterized from that. Those composed SVGs are committed
// (platforms and the README reference them directly) but are generated output -
// edit the master, never them.

import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const RESVG = '@resvg/resvg-js@2.6.2';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'docs', 'assets');
const TOOLS = join(ROOT, 'build', 'icon-tools');

const CANVAS = 1024;
const CENTRE = CANVAS / 2;
const SAFE_RADIUS = 460; // ICON_PLAN section 2

// ---------------------------------------------------------------- palette

const PLATE = '#12161A';
const MINT = '#00E5A3';       // structural stroke, dark contexts
const MINT_DARK = '#008F66';  // ditto, light contexts. 3.72:1 on #F2F4F7
const AMBER = '#FFAC1C';      // family accent, dark contexts
const AMBER_DARK = '#B8730A'; // ditto, light contexts. 3.47:1 on #F2F4F7
const SILVER = '#E8EDF2';

// The small cut keeps the mint rather than going silver-white like the org's does.
// ICON_PLAN section 8 makes the structural stroke the thing that distinguishes one
// product from another; at favicon size that is the only signal left, so borrowing
// the org's silver would make the two marks read as the same product.

const VARIANTS = [
  // v1 - primary, opaque. Platform avatars.
  { file: 'icon', geo: 'geo', plate: PLATE, stroke: MINT, dot: AMBER,
    sizes: [64, 128, 180, 256, 400, 512, 1024] },

  // v2/v3 - transparent, one per theme. A single transparent cut cannot serve both:
  // mint is 1.50:1 on #F2F4F7 and amber 1.71:1, i.e. invisible.
  { file: 'icon-transparent-dark', geo: 'geo', stroke: MINT, dot: AMBER,
    sizes: [64, 128, 256, 512], theme: 'dark' },
  { file: 'icon-transparent-light', geo: 'geo', stroke: MINT_DARK, dot: AMBER_DARK,
    sizes: [64, 128, 256, 512], theme: 'light' },

  // v4 - simplified small cut, <=24px.
  { file: 'icon-small-dark', geo: 'geo-small', stroke: MINT, dot: AMBER,
    sizes: [16, 32], theme: 'dark' },
  { file: 'icon-small-light', geo: 'geo-small', stroke: MINT_DARK, dot: AMBER_DARK,
    sizes: [16, 32], theme: 'light' },

  // v5 - monochrome. Dots are DROPPED, not recoloured: amber on mint is only
  // 1.14:1, so a same-colour dot sitting on a same-colour stroke is a no-op that
  // just fattens the line. SVG only, no raster ladder.
  { file: 'icon-mono-dark', geo: 'geo', stroke: SILVER, dropDots: true, sizes: [] },
  { file: 'icon-mono-light', geo: 'geo', stroke: PLATE, dropDots: true, sizes: [] },
];

// Superseded by the -dark/-light pair and the ladder above.
const STALE = ['icon-transparent.svg', 'icon-transparent-512.png'];

// ---------------------------------------------------------------- master

// Normalise on read. .gitattributes pins these files to LF, but a stray CRLF master
// would otherwise survive into every generated variant as mixed endings plus one
// extra blank line per block, because the leading-newline trim in block() matches
// a bare LF and not a CRLF pair.
const master = readFileSync(join(ASSETS, 'icon-master.svg'), 'utf8')
  .split('\r\n').join('\n');

function block(id) {
  const m = master.match(new RegExp('<g id="' + id + '">([\\s\\S]*?)</g>'));
  if (!m) throw new Error('icon-master.svg has no <g id="' + id + '">');
  if (m[1].includes('<g')) {
    throw new Error(`<g id="${id}"> contains a nested <g>: this extractor stops at the`
      + ' first </g> and would silently truncate the geometry');
  }
  return m[1].replace(/^\n/, '').replace(/\s+$/, '');
}

// ---------------------------------------------------- safe-zone assertion

// Worst-case distance from centre to any inked point, for a rectilinear polyline
// with square caps and miter joins, plus the dots. Guards the whole point of this
// rework: a mark that breaches 460 gets its corners eaten by Hangar's and Discord's
// circular crop, which is exactly how the old icon shipped.
function worstRadius(geo) {
  // This measurer understands axis-aligned polylines and circles, and nothing else.
  // Anything it cannot measure has to be a hard error rather than a silent pass: a
  // gate that quietly ignores new geometry is worse than no gate, because it still
  // prints "ok".
  const unhandled = [...geo.matchAll(/<(\w+)/g)].map((m) => m[1])
    .filter((t) => !['polyline', 'circle'].includes(t));
  if (unhandled.length) {
    throw new Error(`worstRadius cannot measure <${unhandled[0]}>: teach it, or the`
      + ' safe-zone gate will pass artwork it never looked at');
  }
  if (/\btransform=/.test(geo)) {
    throw new Error('transform= in the master: coordinates must be baked in, not'
      + ' transformed. A scale() would also silently rescale stroke-width.');
  }

  const cand = [];
  const polys = [...geo.matchAll(/<polyline points="([^"]+)"[\s\S]*?stroke-width="([\d.]+)"/g)];
  if (!polys.length) throw new Error('no polyline found');

  for (const poly of polys) {
    const pts = poly[1].trim().split(/\s+/).map((p) => p.split(',').map(Number));
    const hw = Number(poly[2]) / 2;

    // square caps at both ends
    for (const [i, j] of [[0, 1], [pts.length - 1, pts.length - 2]]) {
      const [x, y] = pts[i];
      const [xn, yn] = pts[j];
      const len = Math.hypot(x - xn, y - yn);
      const ux = (x - xn) / len;
      const uy = (y - yn) / len;
      const ex = x + ux * hw;
      const ey = y + uy * hw;
      cand.push([ex - uy * hw, ey + ux * hw], [ex + uy * hw, ey - ux * hw]);
    }
    // miter joins: the outer corner is one vertex of the half-width square around
    // the vertex, and taking all four is conservative in the right direction
    for (let k = 1; k < pts.length - 1; k++) {
      const [x, y] = pts[k];
      for (const a of [-1, 1]) for (const b of [-1, 1]) cand.push([x + a * hw, y + b * hw]);
    }
  }

  let worst = 0;
  let at = null;
  for (const [x, y] of cand) {
    const r = Math.hypot(x - CENTRE, y - CENTRE);
    if (r > worst) { worst = r; at = [x, y]; }
  }
  for (const m of geo.matchAll(/<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)"/g)) {
    const r = Math.hypot(+m[1] - CENTRE, +m[2] - CENTRE) + +m[3];
    if (r > worst) { worst = r; at = [+m[1], +m[2]]; }
  }
  return { worst, at };
}

// ---------------------------------------------------------------- compose

function compose(v) {
  let geo = block(v.geo);
  if (v.dropDots) geo = geo.split('\n').filter((l) => !l.includes('<circle')).join('\n');

  const paint = [`stroke="${v.stroke}"`, v.dot ? `fill="${v.dot}"` : null]
    .filter(Boolean).join(' ');
  const plate = v.plate
    ? `\n  <rect width="${CANVAS}" height="${CANVAS}" fill="${v.plate}" />`
    : '';

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}" width="${CANVAS}" height="${CANVAS}">
  <!-- GENERATED by scripts/export-icons.mjs from icon-master.svg. Do not edit.
       Colour and plate are applied here; the master carries geometry only. -->${plate}
  <g ${paint}>
${geo}
  </g>
</svg>
`;
}

// ---------------------------------------------------------------- rasterize

function loadResvg() {
  const entry = join(TOOLS, 'node_modules', '@resvg', 'resvg-js', 'index.js');
  if (!existsSync(entry)) {
    console.log(`  fetching ${RESVG} into build/icon-tools ...`);
    mkdirSync(TOOLS, { recursive: true });
    writeFileSync(join(TOOLS, 'package.json'), '{"private":true}\n');
    // execSync (string form) rather than execFileSync: npm is a .cmd on Windows and
    // needs a shell, but shell + an argv array trips DEP0190.
    //
    // --ignore-scripts: resvg-js ships prebuilt native binaries as per-platform
    // optionalDependencies and needs no install hooks, so nothing here has a reason
    // to execute arbitrary code at install time.
    execSync(`npm install --no-save --ignore-scripts --prefix "${TOOLS}" ${RESVG}`,
      { stdio: 'inherit' });
  }
  return import(pathToFileURL(entry).href);
}

const { Resvg } = await loadResvg();

function hexAt(px, i) {
  return '#' + [px[i], px[i + 1], px[i + 2]]
    .map((c) => c.toString(16).padStart(2, '0')).join('').toUpperCase();
}

// ---------------------------------------------------------------- run

let failures = 0;

console.log(`safe zone (limit ${SAFE_RADIUS}):`);
for (const id of [...new Set(VARIANTS.map((v) => v.geo))]) {
  const { worst, at } = worstRadius(block(id));
  const ok = worst <= SAFE_RADIUS;
  if (!ok) failures++;
  console.log(`  ${id.padEnd(9)}  r = ${worst.toFixed(1)}` +
    ` at (${at.map((n) => n.toFixed(0)).join(',')})  ${ok ? 'ok' : 'BREACH'}`);
}
if (failures) {
  console.error('\nrefusing to export: artwork would be clipped by a circular crop.');
  process.exit(1);
}

console.log('\nvariants:');
for (const v of VARIANTS) {
  const svg = compose(v);
  writeFileSync(join(ASSETS, `${v.file}.svg`), svg);
  const written = [];

  for (const size of v.sizes) {
    const img = new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render();

    // Post-export paint check (ICON_PLAN section 5). Catches a theme cut that
    // silently shipped carrying the other theme's palette.
    //
    // Presence is asserted for the stroke only, never for the dot: at 16px the dot
    // is 2.19px across and antialiasing means no pixel in it ever reaches full
    // opacity, so a presence test on it would fail spuriously. The dot is covered
    // by the absence half instead, which is the direction that actually matters -
    // a leaked colour is a bug, a colour merely not reaching full coverage is not.
    if (v.theme) {
      const forbidden = v.theme === 'light'
        ? { [MINT.toUpperCase()]: 'mint', [AMBER.toUpperCase()]: 'amber' }
        : { [MINT_DARK.toUpperCase()]: 'mint', [AMBER_DARK.toUpperCase()]: 'amber' };
      const other = v.theme === 'light' ? 'dark' : 'light';
      const px = img.pixels;
      let seen = false;
      const leaked = new Set();
      for (let i = 0; i < px.length; i += 4) {
        if (px[i + 3] < 250) continue;
        const hex = hexAt(px, i);
        if (hex === v.stroke.toUpperCase()) seen = true;
        if (forbidden[hex]) leaked.add(forbidden[hex]);
      }
      if (!seen) {
        console.error(`  ${v.file}@${size}: stroke ${v.stroke} not present`);
        failures++;
      }
      if (leaked.size) {
        console.error(`  ${v.file}@${size}: ${other}-theme `
          + `${[...leaked].join(' and ')} leaked into the ${v.theme} cut`);
        failures++;
      }
      if (failures) continue; // do not write artwork that just failed its own check
    }

    writeFileSync(join(ASSETS, `${v.file}-${size}.png`), img.asPng());
    written.push(String(size));
  }
  console.log(`  ${v.file.padEnd(24)}  ${written.join(' ') || '(svg only)'}`);
}

// Only prune once everything above succeeded. Deleting the previous artwork after a
// failed run would leave the tree with neither the old files nor good new ones.
if (failures) {
  console.error(`\n${failures} paint check(s) failed; left the tree as-is.`);
  process.exit(1);
}

for (const f of STALE) {
  const p = join(ASSETS, f);
  if (existsSync(p)) { rmSync(p); console.log(`  removed stale ${f}`); }
}

console.log('\ndone.');
