export const HWID_ERROR = "HWID must be 10–64 characters: ASCII letters, digits, = or -.";

export function isValidHwid(value: string): boolean {
  return value.length >= 10 && value.length <= 64 && !/[^a-zA-Z0-9=-]/.test(value);
}
