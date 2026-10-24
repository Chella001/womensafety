/** Country codes and phone-number helpers shared by the login and registration pages. */

export const COUNTRIES = [
  { code: "IN", name: "India", dial: "91" },
  { code: "US", name: "United States", dial: "1" },
  { code: "CA", name: "Canada", dial: "1" },
  { code: "GB", name: "United Kingdom", dial: "44" },
  { code: "AE", name: "United Arab Emirates", dial: "971" },
  { code: "AU", name: "Australia", dial: "61" },
  { code: "NZ", name: "New Zealand", dial: "64" },
  { code: "SG", name: "Singapore", dial: "65" },
  { code: "MY", name: "Malaysia", dial: "60" },
  { code: "LK", name: "Sri Lanka", dial: "94" },
  { code: "NP", name: "Nepal", dial: "977" },
  { code: "BD", name: "Bangladesh", dial: "880" },
  { code: "PK", name: "Pakistan", dial: "92" },
  { code: "SA", name: "Saudi Arabia", dial: "966" },
  { code: "QA", name: "Qatar", dial: "974" },
  { code: "KW", name: "Kuwait", dial: "965" },
  { code: "ZA", name: "South Africa", dial: "27" },
  { code: "NG", name: "Nigeria", dial: "234" },
  { code: "KE", name: "Kenya", dial: "254" },
  { code: "DE", name: "Germany", dial: "49" },
  { code: "FR", name: "France", dial: "33" },
  { code: "IT", name: "Italy", dial: "39" },
  { code: "ES", name: "Spain", dial: "34" },
  { code: "NL", name: "Netherlands", dial: "31" },
  { code: "ID", name: "Indonesia", dial: "62" },
  { code: "PH", name: "Philippines", dial: "63" },
  { code: "JP", name: "Japan", dial: "81" },
  { code: "BR", name: "Brazil", dial: "55" },
  { code: "MX", name: "Mexico", dial: "52" },
];

/** Fills a <select> with "India (+91)" style options and selects `selected`. */
export function fillCountrySelect(select, selected) {
  select.replaceChildren(
    ...COUNTRIES.map((country) => {
      const option = document.createElement("option");
      option.value = country.dial;
      option.dataset.country = country.code;
      option.textContent = `${country.name} (+${country.dial})`;
      option.selected = country.code === selected;
      return option;
    }),
  );
}

/**
 * Checks a national number; returns an error message or "" when plausible. This is a friendly first line of defence:
 * Firebase and the Java backend (libphonenumber) make the authoritative decision.
 */
export function validateNationalNumber(dial, input) {
  const value = String(input ?? "").trim();
  if (!value) return "Please enter your phone number.";
  if (!/^[0-9 ()\-.]+$/.test(value)) return "Use digits only (spaces and dashes are fine).";
  const digits = nationalDigits(input);
  if (digits.length < 6) return "That phone number looks too short.";
  if (digits.length + dial.length > 15) return "That phone number looks too long.";
  return "";
}

/** Digits only, without the national "0" trunk prefix people often type (e.g. 07911… in the UK). */
export function nationalDigits(input) {
  return String(input ?? "").replace(/\D/g, "").replace(/^0+/, "");
}

/** "91" + "98765 43210" -> "+919876543210" */
export function toE164(dial, input) {
  return `+${dial}${nationalDigits(input)}`;
}

/** "+919876543210" -> "+91 ••••• ••210" (used when telling the user where a code was sent). */
export function maskE164(e164) {
  const value = String(e164 ?? "");
  if (value.length < 8) return value;
  return `${value.slice(0, 3)} ${"•".repeat(Math.max(value.length - 6, 3))}${value.slice(-3)}`;
}
