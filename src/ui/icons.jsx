/**
 * icons.jsx
 * ---------
 * Every inline SVG the app draws, in one place.
 *
 * They live together (rather than beside the screen that happens to use
 * them first) because icons are the one thing that genuinely IS shared
 * chrome: the pencil appears on Home and in the nav, the speaker in the
 * mixer, the info dot in the beat list. Hunting for "where is the lock
 * drawn" should be one file, not four.
 *
 * All of them are stroke-based on `currentColor`, so the calling button's
 * colour drives them and no icon needs a colour prop.
 */

// Padlock for the tempo lock — open when free, closed when locked. An icon
// (not the words Lock/Locked) so the button's size never changes with state,
// which would resize the flexible slider beside it.
export function LockIcon({ locked }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      {locked
        ? <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        : <path d="M8 11V7a4 4 0 0 1 7.7-1.5" />}
    </svg>
  );
}

export function HomeIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 10.2 12 3l9 7.2V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />
    </svg>
  );
}

export function CogIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function BeatsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <path d="M4 6v12M9 9v6M14 4v16M19 8v8" />
    </svg>
  );
}

// Scholar's cap for the Learn tab. Drawn narrower than the 24-box allows
// (3 → 21 rather than edge to edge) so its wide mortarboard doesn't read as
// heavier than the home and cog icons sitting beside it in the nav.
export function CapIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 4 3 8.5l9 4.5 9-4.5z" />
      <path d="M6.5 10.8V15c0 1.4 2.5 2.6 5.5 2.6s5.5-1.2 5.5-2.6v-4.2" />
      <path d="M21 8.5V14" />
    </svg>
  );
}

export function PencilIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
    </svg>
  );
}

export function InfoIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5" />
      <path d="M12 7.5h.01" />
    </svg>
  );
}

export function MixerIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
      <path d="M5 21v-6M5 11V3M12 21v-10M12 7V3M19 21v-4M19 13V3" />
      <path d="M2 14h6M9 10h6M16 16h6" />
    </svg>
  );
}

export function SpeakerIcon({ muted }) {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 5 6 9H3v6h3l5 4z" />
      {muted
        ? <path d="m16 9 6 6M22 9l-6 6" />
        : <path d="M15.5 8.5a5 5 0 0 1 0 7" />}
    </svg>
  );
}

export function BackIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M15 5 8 12l7 7" stroke="currentColor" strokeWidth="1.8"
        strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function CheckIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 12.5 9.5 18 20 6.5" />
    </svg>
  );
}

// Share — the three-node "send this onward" glyph, not an upload arrow: it
// reads the same on iOS and Android, where the platform share sheets differ.
export function ShareIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="18" cy="5.5" r="2.5" />
      <circle cx="6" cy="12" r="2.5" />
      <circle cx="18" cy="18.5" r="2.5" />
      <path d="M8.2 10.8 15.8 6.7" />
      <path d="M8.2 13.2 15.8 17.3" />
    </svg>
  );
}

// Globe for the Browse tab. It marks the one tab whose beats come from
// OUTSIDE this device — today by pasted code, later from the community
// library (PROJECT_PLAN §7), which is why it isn't a paste/clipboard glyph.
export function GlobeIcon({ size = 16 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3c2.5 2.6 3.8 5.6 3.8 9S14.5 18.4 12 21c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3Z" />
    </svg>
  );
}

// Filled transport glyphs — solid, not stroked, so they read at a glance
// on the clay play bar. `size` because the landscape rail runs smaller.
export function PlayIcon({ size = 22 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M8 4.5 20 12 8 19.5 Z" fill="currentColor" />
    </svg>
  );
}

export function PauseIcon({ size = 22 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="4" width="5" height="16" rx="1.5" fill="currentColor" />
      <rect x="14" y="4" width="5" height="16" rx="1.5" fill="currentColor" />
    </svg>
  );
}

// The Beats page's Start button uses a slightly tighter play triangle.
export function StartIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 5 19 12 7 19 Z" fill="currentColor" />
    </svg>
  );
}

// Membership check for the add-beats sheet — square, clay tick when in.
export function CheckDot({ checked }) {
  return (
    <span aria-hidden="true" style={{ flexShrink: 0, width: 24, height: 24, borderRadius: 8,
      display: "grid", placeItems: "center", fontSize: 15, fontWeight: 800, lineHeight: 1,
      border: `2px solid ${checked ? "var(--clay)" : "var(--rule)"}`,
      background: checked ? "var(--clay)" : "transparent",
      color: "var(--on-clay)" }}>
      {checked ? "✓" : ""}
    </span>
  );
}

// Selection radio for the beat list — hollow ring, saffron dot when chosen.
export function RadioDot({ selected }) {
  return (
    <span aria-hidden="true" style={{ flexShrink: 0, width: 24, height: 24, borderRadius: "50%",
      display: "grid", placeItems: "center",
      border: `2px solid ${selected ? "var(--accent-action)" : "var(--rule)"}` }}>
      {selected && <span style={{ width: 12, height: 12, borderRadius: "50%", background: "var(--accent-action)" }} />}
    </span>
  );
}
