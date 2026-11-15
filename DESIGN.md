---
name: "KECE 课测"
description: "Precision lab console aesthetic for multi-domain algorithm, SQL, frontend AI, and objective technical assessment."
colors:
  calibration-teal: "#137969"
  calibration-teal-hover: "#0e6053"
  calibration-teal-ink: "#0f5f53"
  calibration-teal-soft: "#eef6f4"
  paper-white: "#ffffff"
  instrument-slate: "#f8fafc"
  panel-surface: "#f4f5f7"
  surface-hover: "#ebeef2"
  carbon-ink: "#111111"
  graphite-button: "#1e1e1e"
  slate-secondary: "#555555"
  muted-tertiary: "#8a8a8a"
  hairline-border: "#e4e7ec"
  rule-line: "#ced4da"
  terminal-obsidian: "#141415"
  verdict-success: "#16794f"
  verdict-success-soft: "#eefbf3"
  telemetry-warning: "#92400e"
  telemetry-warning-soft: "#fff7ed"
  verdict-danger: "#c4314b"
  verdict-danger-soft: "#fdf2f4"
typography:
  display:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "46px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.6px"
  headline:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "clamp(24px, 2.3vw, 30px)"
    fontWeight: 700
    lineHeight: 1.25
    letterSpacing: "-0.025em"
  title:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-0.01em"
  body:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "normal"
  label:
    fontFamily: "ui-monospace, 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace"
    fontSize: "11px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0.06em"
    fontFeature: "tnum"
rounded:
  none: "0px"
spacing:
  xs: "6px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.calibration-teal}"
    textColor: "{colors.paper-white}"
    rounded: "{rounded.none}"
    padding: "0 13px"
    height: "36px"
  button-primary-hover:
    backgroundColor: "{colors.calibration-teal-hover}"
  button-dark:
    backgroundColor: "{colors.graphite-button}"
    textColor: "{colors.paper-white}"
    rounded: "{rounded.none}"
    padding: "10px 22px"
  button-ghost:
    backgroundColor: "{colors.paper-white}"
    textColor: "{colors.carbon-ink}"
    rounded: "{rounded.none}"
    padding: "0 12px"
    height: "36px"
  card-hud:
    backgroundColor: "{colors.paper-white}"
    textColor: "{colors.carbon-ink}"
    rounded: "{rounded.none}"
  card-surface:
    backgroundColor: "{colors.panel-surface}"
    textColor: "{colors.carbon-ink}"
    rounded: "{rounded.none}"
    padding: "16px 20px"
  input-search:
    backgroundColor: "{colors.paper-white}"
    textColor: "{colors.carbon-ink}"
    rounded: "{rounded.none}"
    padding: "6px 12px"
    height: "40px"
  badge-telemetry:
    backgroundColor: "{colors.instrument-slate}"
    textColor: "{colors.slate-secondary}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "2px 6px"
  nav-item-active:
    backgroundColor: "{colors.calibration-teal-soft}"
    textColor: "{colors.calibration-teal-ink}"
    rounded: "{rounded.none}"
    padding: "9px 14px"
---

# Design System: KECE 课测

## Overview

**Creative North Star: "The Precision Lab Console"**

KECE treats online technical assessment like a calibrated benchtop instrument rather than a playful consumer portal or a dark-mode hacker cliché. On light paper surfaces overlaid with faint 20px engineering graph grids, every question, cluster pool status, and sandbox verdict reads at a glance with strict tabular alignment, crisp 1px borders, and zero ornamental curves.

The visual system prioritizes verdict legibility and high-concurrency exam calm over decorative flourish. Monospace telemetry tags (`SYS.ONLINE`, `BANK.READY`, `POOLS: X/Y READY`), deterministic state indicators, and left/top accent rules anchor the user's attention on what is live, runnable, and graded. Soft rounded SaaS cards, gradient marketing blobs, and glowing dark neon dashboards are explicitly rejected.

**Key Characteristics:**
- Light-first engineering grid canvases paired with strict zero-radius (`0px`) geometry across every shell, card, button, badge, input, and embedded Monaco editor.
- Monochrome structural discipline (`#111111` ink on `#ffffff` / `#f8fafc` surfaces) punctuated sparingly by Calibration Teal (`#137969`) for live readiness and primary operational triggers.
- Tabular monospace telemetry (`font-variant-numeric: tabular-nums`) for pool counters, pagination readouts, timestamps, and score fractions.
- Tactile & crisp component physics: hairline `1px` borders, `2px` top HUD rules, `3px` active left rails, and brisk `0.15s`–`0.22s` state shifts guarded by `prefers-reduced-motion`.

## Colors

A restrained monochrome paper-and-carbon palette anchored by a single Calibration Teal accent and high-contrast functional telemetry hues.

### Primary
- **Calibration Teal** (`#137969`): Reserved for live system readiness dots, active HUD top rules (`2px`), primary HUD action buttons (such as `随机刷 5 题`), focus outlines (`2px`), and active navigation indicators.
- **Calibration Teal Hover** (`#0e6053`): Pressed and hovered fill state for primary Calibration Teal actions.
- **Calibration Teal Ink** (`#0f5f53`): Darkened teal foreground for text inside tinted badges, active admin navigation items, and status pills so text stays high-contrast.
- **Calibration Teal Soft** (`#eef6f4`): Subtle mint-slate wash used for row hover highlights, active navigation backgrounds, and soft status chips.

### Neutral
- **Paper White** (`#ffffff`): Primary viewport background (`--bg`) and elevated HUD card canvas (`--surface-white`) overlaid with subtle `rgba(17, 17, 17, 0.025–0.03)` graph lines.
- **Instrument Slate** (`#f8fafc`): Muted header, table thead, footer strip, and secondary HUD band background (`--kece-surface-muted`).
- **Panel Surface** (`#f4f5f7`): Legacy secondary panel and table-cell hover fill (`--surface`) alongside Paper White (`#ffffff`) and Instrument Slate (`#f8fafc`).
- **Surface Hover** (`#ebeef2`): Hover state and skeleton block fill (`--surface-hover`) over light surfaces.
- **Carbon Ink** (`#111111`): Primary headings, body text, table row titles, and active border highlights (`--ink`).
- **Graphite Button** (`#1e1e1e`): Primary portal dark CTA fill (`--button`), strong divider borders (`--line-strong`), and high-contrast admin pills.
- **Slate Secondary** (`#555555`): Body descriptions, table column headers, inactive navigation labels, and secondary metadata (`--ink-secondary`).
- **Muted Tertiary** (`#8a8a8a`): Placeholder and non-critical footnote token (`--ink-tertiary`). Never use for interactive labels, breadcrumbs, or status copy where light-surface contrast drops below `4.5:1`.
- **Hairline Border** (`#e4e7ec`): Primary container, list card, and table cell divider stroke (`--kece-border`).
- **Rule Line** (`#ced4da`): Legacy input and toolbar structural border (`--line`).
- **Terminal Obsidian** (`#141415`): Dedicated high-contrast dark sandbox demonstration canvas on the homepage (`HomeView.vue`), pairing syntax-highlighted code with a dark telemetry footer.

### Telemetry & Verdict Accents
- **Verdict Success** (`#16794f`) on **Verdict Success Soft** (`#eefbf3`): Accepted verdicts (`--success`, `--kece-success`), ready indicators, published states, and solved progress states.
- **Telemetry Warning** (`#92400e`) on **Telemetry Warning Soft** (`#fff7ed`): Stale cache banners, favorited toggles, draining pools, queue backlogs, and medium difficulty cues (`--kece-warning` on `--kece-warning-soft`, `6.76:1` contrast).
- **Verdict Danger** (`#c4314b`) on **Verdict Danger Soft** (`#fdf2f4`): Offline nodes, validation errors (`--danger`, `--kece-danger`), destructive confirmations, and hard difficulty/alert states.

### Named Rules
**The Calibration Accent Rule.** Calibration Teal (`#137969`) signals live readiness, focus, and primary operational intent—it is never used as decorative background wallpaper or competing rainbow tags across question rows.

**The High-Contrast Telemetry Rule.** Tinted status chips and navigation states pair soft background washes (`#eef6f4`, `#eefbf3`, `#fff7ed`, `#fdf2f4`) with darkened ink tokens (`#0f5f53`, `#16794f`, `#92400e`, `#c4314b`), never raw pastel text on light gray.

## Typography

**Display Font:** `'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif`
**Body Font:** `'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif`
**Label/Mono Font:** `ui-monospace, 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace`

**Character:** Crisp CJK system sans paired with geometric monospace telemetry. Headlines stay tight in tracking (`-0.025em` to `-0.6px`) and heavy (`700`), while telemetry eyebrows, problem numbers, and numeric counters switch to uppercase monospace with fixed-width figures.

### Hierarchy
- **Display** (`700`, `46px` desktop / `34px` mobile, `1.2` line-height, `-0.6px` tracking): Landing Hero headline (`HomeView.vue`).
- **Headline** (`700`, `clamp(24px, 2.3vw, 30px)` HUD / `26px` list headers, `1.25` line-height, `-0.025em` tracking): Section titles (`今天，解一道题`) and bank page titles (`算法编程`, `数据库实践`, `客观题`).
- **Title** (`700`, `17px–18px`, `1.3` line-height): Console headers (`评测状态`), panel subheadings (`选择下一道练习`), and question row titles (`15px–16px`, weight `600`).
- **Body** (`400`, `15px` global / `14px` descriptions, `1.6` line-height): UI copy, problem summaries, and Markdown prose (`65ch–75ch` comfortable reading width inside `.prose`).
- **Label** (`600–700`, `10px–12px`, `1.2` line-height, `+0.04em` to `+0.08em` tracking, uppercase monospace): HUD tags (`CLUSTER HUD`, `PRACTICE HUD`), code eyebrows (`// DAILY PRACTICE`), table column heads, and telemetry codes (`SYS.ONLINE`, `BANK.READY`).

### Named Rules
**The Tabular Telemetry Rule.** Any container displaying counts, pagination fractions, node ratios, or timestamps (`ClusterConsole`, `HomePracticeBoard`, progress cards) enforces `font-variant-numeric: tabular-nums` so numbers never jitter during polling or state transitions.

## Layout

KECE operates in an application shell pairing a sticky left navigation rail (`AppSidebar`: `236px` expanded, `68px` collapsed) with a `50px` sticky translucent topbar (`rgba(255, 255, 255, 0.88)` + `backdrop-filter: blur(12px)`) and a centered `1120px` working container (`24px` horizontal padding).

- **Hero Split (`HomeView`):** Asymmetric `1fr 1.05fr` CSS grid (`48px` gap) placing copy left and `ClusterConsole` right; collapses to a single column at `<= 860px`.
- **HUD Rhythm:** Internal gutters scale smoothly via `clamp(12px, 1.4vw, 20px)` on `ClusterConsole` and `clamp(16px, 2.2vw, 32px)` on `HomePracticeBoard`. List rows use fixed alignment tracks (`72px minmax(0, 1fr) 72px 28px`) above `780px`, tightening to `52px minmax(0, 1fr) 60px 20px` on smaller screens.
- **List & Portal Pages (`Questions`, `DatabaseQuestions`, `ObjectiveQuestions`, `ProblemSets`, `Rankings`, `Announcements`, `Practice`, `Exams`, `Profile`):** Shared `QuestionListShell` / `question-bank.css` header + progress bar (`tabular-nums`), `.toolbar` filter bar, flat `#ffffff` row/board cards separated by `#e4e7ec` hairlines, inline `.state-banner` retry panels, and shared `Pagination.vue`.
- **Responsive Breakpoints:** `860px` (Hero/CTA stack & admin drawer), `780px` / `768px` (practice board & list toolbar wrap, hide trailing CTA text), `640px` (Hero metrics stack, terminal tabs collapse), `480px` (compact HUD gutters, hide `.hud-tag` / `.live-label`).

## Elevation & Depth

Surfaces are flat by default and separated by `1px` crisp hairline borders (`#e4e7ec` / `#ced4da`) plus subtle tonal alternation between Paper White (`#ffffff`) and Instrument Slate (`#f8fafc`). Ambient shadows appear sparingly to lift primary interactive HUD instruments, the dark terminal showcase, overlays, and modals off the flat canvas.

### Shadow Vocabulary
- **Surface Subtle (`--shadow-sm`)** (`box-shadow: 0 1px 2px rgba(17, 17, 17, 0.04)`): Baseline depth for `.card` toolbars and CTA containers.
- **HUD Resting** (`box-shadow: 0 12px 28px rgba(0, 0, 0, 0.07), 0 2px 6px rgba(0, 0, 0, 0.04)`): Grounding shadow for `HomePracticeBoard` and `ClusterConsole` (which deepens on hover to `0 18px 36px rgba(0, 0, 0, 0.14)`).
- **Modal / Overlay (`--shadow-md`)** (`box-shadow: 0 8px 24px rgba(17, 17, 17, 0.06)`): Floating `ConfirmModal` and `ToastHost` notifications.
- **Flyout Hard** (`box-shadow: 4px 4px 0 rgba(0, 0, 0, 0.08)`): Sharp offset drop shadow for collapsed `AppSidebar` popover menus.

### Named Rules
**The Flat-By-Default Rule.** Standard list rows, toolbars, and tables sit completely flat inside `1px` borders; elevation is earned only by interactive HUD instruments, overlays, or hover focus.

## Shapes

Every surface, control, pill, badge, input, modal, and third-party Monaco editor widget obeys strict right-angle geometry (`0px` corner radius). Hierarchy comes from border weights (`1px` standard vs `2px` HUD top cap vs `3px` active left accent bar) and subtle `20px × 20px` CSS linear-gradient coordinate grid backdrops on HUD cards.

### Named Rules
**The Zero-Radius Invariant.** Both `--radius: 0`, `--pill: 0`, and `--kece-card-radius: 0px` prohibit rounded rectangles on buttons, inputs, tags, and cards (`border-radius: 50%` is permitted strictly on micro status rings such as `.unsolved-ring` and `.dot-status` indicators).

## Components

Tactile and crisp controls with immediate border/tint feedback and explicit keyboard focus rings (`outline: 2px solid #137969`).

### Buttons
- **Shape:** Sharp rectangular (`0px` radius).
- **Primary (HUD / Modernized Views):** Calibration Teal (`#137969`) fill, Paper White (`#ffffff`) text, `36px` min-height, `0 13px` padding, `600` weight; darkens to `#0e6053` on hover.
- **Primary (Legacy Portal / Hero):** Graphite (`#1e1e1e`) fill (`--button`), Paper White (`#ffffff`) text, `10px 22px` padding (`14px 30px` for `.btn-lg`).
- **Ghost / Secondary:** Paper White (`#ffffff`) or transparent fill, `1px solid #e4e7ec` / `#ced4da` border, Carbon Ink (`#111111`) or Slate Secondary (`#555555`) text; shifts to `#f8fafc` or `#eef6f4` with darkened border on hover.

### Chips / Badges
- **Telemetry Tags (`.hud-tag`, `.live-label`):** Instrument Slate (`#f8fafc`) or `#ffffff` fill, `1px` border, `10px–11px` uppercase monospace, `2px 6px` padding.
- **Status & Semantic Pills (`.hud-pill` in `style.css`):** Unified global status pill (`tone-teal`, `tone-success`, `tone-warning`, `tone-danger`, `tone-neutral`) pairing `1px` borders, soft tinted washes (`#eef6f4`, `#eefbf3`, `#fff7ed`, `#fdf2f4`, `#f8fafc`), `5px × 5px` square `::before` pips, and AA ink (`#0f5f53`, `#16794f`, `#92400e`, `#b4233d`, `#475569`) across all banks, problem sets, practices, exams, and profile tables.
- **Error Recovery Banners (`.state-banner`, `.submit-error`, `.btn-inline-retry`):** Bordered `--danger-bg` (`#fdf2f4`) / `--danger` (`#c4314b`) inline alert strips with immediate keyboard-accessible retry actions.

### Cards / Containers
- **HUD Instrument Card (`.practice-card`, `.cluster-console`):** Paper White (`#ffffff`) over `20px × 20px` engineering gridlines, `1px` hairline border, `2px solid #137969` top accent bar, zero radius, ambient HUD shadow.
- **Legacy Surface Card (`.card`, `.question-item`):** Panel Surface (`#f4f5f7`) fill, `1px solid #ced4da` border, `16px 20px` padding, `--shadow-sm`.

### Inputs / Fields
- **Style:** `40px`–`48px` height, Paper White (`#ffffff`) or Panel Surface (`#f4f5f7`) background, `1px` border, `0px` radius, prefix icon inside `.search-field` / `.field`.
- **Focus:** Border shifts to `#137969` with `0 0 0 2px rgba(19, 121, 105, 0.14)` halo on HUD inputs, or `border-color: #111111` on legacy `.input`.

### Navigation
- **AppSidebar:** Fixed `236px` (or `68px` collapsed) sticky rail, `#ffffff` canvas, `42px` row height, `3px` left active indicator (`#111111` in public portal; `#137969` on `#eef6f4` in `AdminLayout`), expandable accordions for `题库` and `题集`, and `4px 4px 0` hard-shadowed flyouts when collapsed.
- **Topbar:** `50px` translucent frosted bar displaying monospace breadcrumb (`KECE // PORTAL / SECTION`) left and live `POOLS: X/Y READY` telemetry pill right.

### Signature Components
- **ClusterConsole (`ClusterConsole.vue`):** Live judging pool telemetry monitor combining a pulsing `hud-dot`, 3-column summary metrics (`可用专池`, `评测中`, `排队中`) with animated readiness meter bar, a 5-column pool table with monotonic `260ms cubic-bezier(0.22, 1, 0.36, 1)` drop entry (no bounce overshoot, zero resting `will-change`), and a desktop hover tilt disabled under `prefers-reduced-motion` and narrow viewports.
- **HomePracticeBoard (`HomePracticeBoard.vue`):** Dual-mode public practice HUD supporting live debounced search + 4-row pagination (`browse`) and one-click cross-page `随机刷 5 题` unsolved-priority sampling (`random5`), sharing the `20px` engineering grid, `2px` teal crown, and `60ms` staggered row cascade.

## Do's and Don'ts

### Do:
- **Do** enforce `0px` border radius (`--radius: 0`, `--kece-card-radius: 0px`) across every card, button, badge, input, table, and modal.
- **Do** apply `font-variant-numeric: tabular-nums` and `var(--font-mono)` to all IDs, telemetry readouts, timers, ratios, scores, and pagination counts.
- **Do** guard every cascade, scanline, tilt, `scrollIntoView`, or pulse animation with `prefers-reduced-motion: reduce` and keep hover/focus transitions snappy (`0.15s–0.26s`).
- **Do** reuse global primitives (`.hud-pill.tone-*`, `.state-banner.is-error` + `.btn-inline-retry`, `<Pagination>`, `questionMeta`, `plainText`) instead of page-local duplicated badges, spinners, or markdown strippers.

### Don't:
- **Don't** introduce rounded pills, `border-radius > 0` cards (outside tiny `50%` status dots), or soft consumer SaaS gradients.
- **Don't** hardcode uncalibrated saturated colors (such as `#1d4ed8` blue chips, `#e67700` orange pills, or `#7048e8` purple tags) on light surfaces alongside Calibration Teal HUD elements.
- **Don't** use `--ink-tertiary` (`#8a8a8a`) for small interactive labels or essential status text on `#ffffff` or `#f4f5f7` backgrounds where contrast drops below 4.5:1.
- **Don't** hide focus outlines or build click-only `<div role="button">` / clickable `<span>` tags without native `<button>` semantics and keyboard `:focus-visible` rings.
