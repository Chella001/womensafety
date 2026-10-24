/** One shared, cached request for the SOS settings (TEST/REAL mode and the contact limit). */

import { api } from "./api.js";

const FALLBACK = { mode: "UNKNOWN", testMode: false, maxContacts: 5, note: "" };
let cached = null;

/** Never rejects: when the server can't be asked, the cautious defaults apply and `mode` is "UNKNOWN". */
export function loadSosInfo(getToken) {
  if (!cached) {
    cached = getToken()
      .then((token) => api.sosInfo(token))
      .catch(() => {
        cached = null; // try again next time
        return FALLBACK;
      });
  }
  return cached;
}
