// TEST DOUBLE of the two Firebase JS SDK modules the app loads from www.gstatic.com. It implements only what
// frontend/js/auth.js uses. Sign-in rules:
//   - any number whose digits end in 0000 is rejected as invalid;
//   - the code 123456 is accepted, anything else is "invalid-verification-code";
//   - the signed-in user is kept in sessionStorage (per tab), uid = "u" + the digits of the number;
//   - the ID token is "fake-<uid>", which the mock API accepts.

export const firebaseApp = `
export function initializeApp(options) { return { name: "[DEFAULT]", options }; }
export function getApps() { return []; }
`;

export const firebaseAuth = `
const KEY = "fakeFirebaseUser";
const error = (code) => { const e = new Error("Firebase: Error (" + code + ")."); e.code = code; e.name = "FirebaseError"; return e; };
class FakeUser {
  constructor(data) { this.uid = data.uid; this.phoneNumber = data.phone; }
  async getIdToken() { return "fake-" + this.uid; }
}
const stored = () => { try { return JSON.parse(sessionStorage.getItem(KEY)); } catch { return null; } };
const auth = {
  settings: {},
  currentUser: null,
  async authStateReady() { const d = stored(); this.currentUser = d ? new FakeUser(d) : null; },
};
export function getAuth() { const d = stored(); auth.currentUser = d ? new FakeUser(d) : null; return auth; }
export class RecaptchaVerifier { constructor() {} clear() {} render() { return Promise.resolve(0); } }
export async function signInWithPhoneNumber(_auth, phone) {
  const digits = String(phone).replace(/\\D/g, "");
  if (/0000$/.test(digits)) throw error("auth/invalid-phone-number");
  return {
    verificationId: "fake-verification",
    async confirm(code) {
      if (code !== "123456") throw error("auth/invalid-verification-code");
      const data = { uid: "u" + digits, phone };
      sessionStorage.setItem(KEY, JSON.stringify(data));
      auth.currentUser = new FakeUser(data);
      return { user: auth.currentUser };
    },
  };
}
export async function signOut() { sessionStorage.removeItem(KEY); auth.currentUser = null; }
`;
