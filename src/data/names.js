/**
 * names.js
 * --------
 * The one length limit for anything a person names: beats and lists.
 *
 * 255 is generous for a name and matches the Android and iOS apps, so a beat
 * named on one device never arrives somewhere it can't be saved. The inputs
 * stop typing at the limit (`maxLength`); `clampName` is the backstop for
 * names that arrive any other way (imports, the " (2)" de-duplication suffix).
 */

export const MAX_NAME_LEN = 255;

/**
 * Cut a name to at most `max` UTF-16 units (what `maxLength` counts), without
 * leaving half of a surrogate pair dangling at the end.
 */
export function clampName(name, max = MAX_NAME_LEN) {
  if (typeof name !== "string" || name.length <= max) return name;
  let cut = name.slice(0, max);
  const last = cut.charCodeAt(cut.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) cut = cut.slice(0, -1);
  return cut;
}
