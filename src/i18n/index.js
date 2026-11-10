/**
 * i18n — every string the interface shows, in the user's language.
 * ----------------------------------------------------------------
 * Screens never hold English text: they call t("some.key"), and the key is
 * looked up in the dictionary for the user's language (`es.js`, …), falling
 * back to `en.js` key by key. So a half-finished translation shows English
 * for the missing lines rather than blanks or raw keys.
 *
 * The language is picked ONCE, at load, from the browser's preferred
 * languages (navigator.languages, in order), matching on the primary subtag:
 * "es-MX" and "es-419" both get Spanish. Nothing switches language while the
 * app is open, so this needs no React context: a plain module is enough.
 * `?lang=es` in the URL forces a language, for testing and for sharing a
 * link to a Spanish speaker on an English machine.
 *
 * Values are strings with {placeholders}, or functions of the variables for
 * the few lines that need grammar (plurals): t("beats.count", { n: 3 }).
 *
 * NOT translated, on purpose: the app's name, beat and progression names,
 * bols (Ta, Ge, …) and anything a user typed. Those are names, not interface.
 *
 * Adding a language: copy es.js, translate, add it to DICTS. `npm run
 * check-i18n` lists keys a language is missing or has left over.
 */
import en from "./en.js";
import es from "./es.js";

export const DICTS = { en, es };
export const DEFAULT_LOCALE = "en";

/** The first of `preferred` that we have a dictionary for, else English. */
export function pickLocale(preferred, available = Object.keys(DICTS)) {
  for (const tag of preferred) {
    const base = String(tag || "").toLowerCase().split(/[-_]/)[0];
    if (available.includes(base)) return base;
  }
  return DEFAULT_LOCALE;
}

function detect() {
  if (typeof window === "undefined") return DEFAULT_LOCALE;
  const forced = new URLSearchParams(window.location.search).get("lang");
  const nav = window.navigator;
  const preferred = nav.languages?.length ? nav.languages : [nav.language];
  return pickLocale(forced ? [forced, ...preferred] : preferred);
}

export const locale = detect();

/** Fill {name} placeholders from `vars`. */
function fill(text, vars) {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, name) => (name in vars ? String(vars[name]) : m));
}

/** Look `key` up in `dict`, falling back to English, then to the key itself. */
export function translate(dict, key, vars) {
  const value = dict[key] ?? en[key];
  if (value == null) {
    if (import.meta.env?.DEV) console.warn(`i18n: missing key "${key}"`);
    return key;
  }
  return typeof value === "function" ? value(vars ?? {}) : fill(value, vars);
}

/** The interface string for `key` in the user's language. */
export function t(key, vars) {
  return translate(DICTS[locale], key, vars);
}

/** `key` if a dictionary has it, else `fallback` untouched (no warning). */
export function tOr(key, fallback, vars) {
  const known = DICTS[locale][key] ?? en[key];
  return known == null ? fallback : translate(DICTS[locale], key, vars);
}

/*
 * Shipped beat text. A built-in's tag ("Everyday"), its group heading and its
 * description are interface in all but name, so they translate by value or
 * id; anything a user typed has no key and passes through as written.
 */
export const noteLabel = (note) => (note ? tOr(`note.${note}`, note) : note);
export const groupLabel = (group) => (group ? tOr(`group.${group}`, group) : group);
export const beatDescription = (beat) => tOr(`desc.${beat.id}`, beat.description);
