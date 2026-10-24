/**
 * Emergency contacts: list, add, edit (dialog), verify and remove (with confirmation). Adding a contact notifies nobody.
 * A contact only receives SOS messages once it is verified (it entered a code we texted to it) AND the user confirmed it agreed.
 */

import { api, ApiError } from "./api.js";
import { loadSosInfo } from "./sos-info.js";
import {
  clearErrorOnInput,
  element,
  hideAlert,
  icon,
  initials,
  setBusy,
  showAlert,
  showServerFieldErrors,
  telHref,
  validateFields,
  validateFullName,
} from "./ui.js";

export const DEFAULT_MAX_CONTACTS = 5;

export const contactValidators = {
  name: (value) => validateFullName(value),
  phone(value) {
    const phone = value.trim();
    if (!phone) return "Please enter a phone number.";
    if (!/^\+?[0-9 ()-]+$/.test(phone)) return "Use only digits, spaces, ( ), - and a leading +.";
    const digits = phone.replace(/\D/g, "").length;
    if (digits < 7 || digits > 15) return "Enter a phone number with 7 to 15 digits.";
    return "";
  },
  relationship: (value) => (value.trim().length > 40 ? "Keep the relationship under 40 characters." : ""),
};

/** Short status for a contact: whether it may receive SOS messages, and what is missing. */
export function contactStatus(contact) {
  if (contact.eligible) return ["Verified · will be messaged", "ok"];
  if (contact.verified) return ["Verified · messages off (no consent)", "warn"];
  if (contact.notifyConsent) return [contact.verificationPending ? "Code sent · not verified yet" : "Not verified · won't be messaged", "warn"];
  return ["Not verified · no consent · won't be messaged", "off"];
}

/** One list row. Pass `actions` ({onEdit, onRemove}) to show the edit and remove buttons; omit for call-only rows. */
export function contactRow(contact, actions) {
  const item = element("li", "contact");
  const avatar = element("span", "avatar", initials(contact.name));
  avatar.setAttribute("aria-hidden", "true");

  const info = element("div", "contact__info");
  info.append(element("span", "contact__name", contact.name));
  info.append(element("span", "contact__meta", [contact.relationship, contact.phoneNumber].filter(Boolean).join(" · ")));
  const [statusText, statusKind] = contactStatus(contact);
  info.append(element("span", `pill pill--${statusKind} contact__status`, statusText));

  const buttons = element("div", "contact__actions");
  const call = element("a", "icon-btn");
  call.href = telHref(contact.phoneNumber);
  call.setAttribute("aria-label", `Call ${contact.name}`);
  call.append(icon("phone"));
  buttons.append(call);

  if (actions) {
    if (!contact.verified && actions.onVerify) {
      const verify = element("button", "btn btn--outline btn--small");
      verify.type = "button";
      verify.textContent = "Verify";
      verify.setAttribute("aria-label", `Verify ${contact.name}`);
      verify.addEventListener("click", () => actions.onVerify(contact, verify));
      buttons.append(verify);
    }
    const edit = element("button", "icon-btn");
    edit.type = "button";
    edit.setAttribute("aria-label", `Edit ${contact.name}`);
    edit.append(icon("edit"));
    edit.addEventListener("click", () => actions.onEdit(contact, edit));
    const remove = element("button", "icon-btn icon-btn--danger");
    remove.type = "button";
    remove.setAttribute("aria-label", `Remove ${contact.name}`);
    remove.append(icon("trash"));
    remove.addEventListener("click", () => actions.onRemove(contact, remove));
    buttons.append(edit, remove);
  }

  item.append(avatar, info, buttons);
  return item;
}

/**
 * @param {{ getToken: () => Promise<string>, onUnauthorized: () => void }} options
 * @returns {{ getContacts: () => object[] }}
 */
export function initEmergencyContacts({ getToken, onUnauthorized }) {
  const $ = (id) => document.getElementById(id);
  const list = $("contact-list");
  const empty = $("contacts-empty");
  const loading = $("contacts-loading");
  const count = $("contact-count");
  const alertBox = $("contacts-alert");
  const form = $("contact-form");
  const nameInput = $("contact-name");
  const phoneInput = $("contact-phone");
  const relationshipInput = $("contact-relationship");
  const consentInput = $("contact-consent");
  const submit = $("contact-submit");

  let contacts = [];
  let adding = false;
  let maxContacts = DEFAULT_MAX_CONTACTS;
  loadSosInfo(getToken).then((info) => {
    maxContacts = info.maxContacts ?? DEFAULT_MAX_CONTACTS;
    render();
  });

  function fail(error, fallback, box = alertBox) {
    if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) {
      onUnauthorized(error);
      return;
    }
    showAlert(box, "error", error instanceof ApiError ? error.message : fallback);
  }

  function render() {
    loading.hidden = true;
    list.replaceChildren(...contacts.map((contact) => contactRow(contact, { onEdit: openEdit, onRemove: askToRemove, onVerify: openVerify })));
    list.hidden = contacts.length === 0;
    empty.hidden = contacts.length !== 0;
    count.textContent = `${contacts.length} / ${maxContacts}`;
    const full = contacts.length >= maxContacts;
    for (const input of [nameInput, phoneInput, relationshipInput, submit]) input.disabled = full;
    if (full) {
      showAlert(alertBox, "info", `You've saved the maximum of ${maxContacts} contacts. Remove one to add another.`);
    } else if (alertBox.dataset.type === "info") {
      hideAlert(alertBox);
    }
  }

  async function load() {
    try {
      contacts = await api.listContacts(await getToken());
      render();
    } catch (error) {
      loading.hidden = true;
      empty.hidden = true;
      fail(error, "We couldn't load your contacts. Please refresh the page.");
    }
  }

  /* ---------- add ---------- */

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (adding) return;
    hideAlert(alertBox);
    const valid = validateFields([
      [nameInput, contactValidators.name],
      [phoneInput, contactValidators.phone],
      [relationshipInput, contactValidators.relationship],
    ]);
    if (!valid) return;

    adding = true;
    setBusy(submit, true, "Adding…");
    try {
      const created = await api.addContact(await getToken(), {
        name: nameInput.value.trim(),
        phoneNumber: phoneInput.value.trim(),
        relationship: relationshipInput.value.trim(),
        notifyConsent: consentInput.checked,
      });
      contacts = [...contacts, created];
      form.reset();
      render();
      nameInput.focus();
    } catch (error) {
      const shown = error instanceof ApiError && showServerFieldErrors(error.details, {
        name: nameInput, phoneNumber: phoneInput, relationship: relationshipInput,
      });
      if (!shown) fail(error, "We couldn't save that contact. Please try again.");
    } finally {
      adding = false;
      setBusy(submit, false);
      submit.disabled = contacts.length >= maxContacts;
    }
  });
  clearErrorOnInput(nameInput, phoneInput, relationshipInput);

  /* ---------- edit ---------- */

  const editDialog = $("edit-dialog");
  const editForm = $("edit-form");
  const editName = $("edit-name");
  const editPhone = $("edit-phone");
  const editRelationship = $("edit-relationship");
  const editAlert = $("edit-alert");
  const editSave = $("edit-save");
  const editConsent = $("edit-consent");
  let editing = null;
  let saving = false;

  function openEdit(contact, trigger) {
    editing = { contact, trigger };
    hideAlert(editAlert);
    editName.value = contact.name;
    editPhone.value = contact.phoneNumber;
    editRelationship.value = contact.relationship ?? "";
    editConsent.checked = Boolean(contact.notifyConsent);
    $("edit-reset-note").hidden = true;
    editDialog.showModal();
    editName.focus();
  }

  $("edit-cancel").addEventListener("click", () => editDialog.close());
  editDialog.addEventListener("click", (event) => {
    if (event.target === editDialog) editDialog.close();
  });
  editDialog.addEventListener("close", () => editing?.trigger.isConnected && editing.trigger.focus());
  clearErrorOnInput(editName, editPhone, editRelationship);
  editPhone.addEventListener("input", () => {
    // Warn before the user loses verification by changing the number.
    $("edit-reset-note").hidden = !editing || editPhone.value.trim() === editing.contact.phoneNumber;
  });

  editForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (saving || !editing) return;
    hideAlert(editAlert);
    const valid = validateFields([
      [editName, contactValidators.name],
      [editPhone, contactValidators.phone],
      [editRelationship, contactValidators.relationship],
    ]);
    if (!valid) return;

    saving = true;
    setBusy(editSave, true, "Saving…");
    try {
      const updated = await api.updateContact(await getToken(), editing.contact.id, {
        name: editName.value.trim(),
        phoneNumber: editPhone.value.trim(),
        relationship: editRelationship.value.trim(),
        notifyConsent: editConsent.checked,
      });
      contacts = contacts.map((c) => (c.id === updated.id ? updated : c));
      editDialog.close();
      render();
    } catch (error) {
      const shown = error instanceof ApiError && showServerFieldErrors(error.details, {
        name: editName, phoneNumber: editPhone, relationship: editRelationship,
      });
      if (!shown) {
        if (error instanceof ApiError && error.status === 404) {
          editDialog.close();
          await load();
        } else {
          fail(error, "We couldn't save your changes. Please try again.", editAlert);
        }
      }
    } finally {
      saving = false;
      setBusy(editSave, false);
    }
  });

  /* ---------- verify ---------- */

  const verifyDialog = $("verify-dialog");
  const verifyAlert = $("verify-alert");
  const verifySend = $("verify-send");
  const verifyForm = $("verify-form");
  const verifyCode = $("verify-code");
  const verifyConfirm = $("verify-confirm");
  let verifying = null; // { contact, trigger }
  let sending = false;
  let confirming = false;

  function openVerify(contact, trigger) {
    verifying = { contact, trigger };
    hideAlert(verifyAlert);
    $("verify-title").textContent = `Verify ${contact.name}`;
    $("verify-intro").textContent = contact.notifyConsent
      ? `We will text a 6-digit code to ${contact.phoneNumber}. Ask ${contact.name} to read it to you, then enter it here. Your own login does not verify their number.`
      : `First confirm that ${contact.name} agreed to receive SafeHer emergency messages (Edit the contact and tick the box), then verify the number.`;
    $("verify-testmode").hidden = true;
    verifyForm.hidden = true;
    verifyCode.value = "";
    verifySend.hidden = false;
    verifySend.disabled = !contact.notifyConsent;
    verifyDialog.showModal();
    (contact.notifyConsent ? verifySend : $("verify-close")).focus();
  }

  async function sendCode() {
    if (sending || !verifying) return;
    sending = true;
    hideAlert(verifyAlert);
    setBusy(verifySend, true, "Sending…");
    try {
      const started = await api.startContactVerification(await getToken(), verifying.contact.id);
      verifySend.hidden = true;
      verifyForm.hidden = false;
      $("verify-testmode").hidden = !started.testMode;
      if (started.testMode) $("verify-testcode").textContent = started.testModeCode;
      if (!started.testMode) showAlert(verifyAlert, "info", started.message); // the TEST MODE banner already says it
      verifyCode.focus();
      await load(); // refresh "code sent" state
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        verifyDialog.close();
        await load();
      } else {
        fail(error, "We couldn't send the code. Please try again.", verifyAlert);
      }
    } finally {
      sending = false;
      setBusy(verifySend, false);
    }
  }
  verifySend.addEventListener("click", sendCode);
  $("verify-resend").addEventListener("click", sendCode);
  $("verify-close").addEventListener("click", () => verifyDialog.close());
  verifyDialog.addEventListener("click", (event) => {
    if (event.target === verifyDialog) verifyDialog.close();
  });
  verifyDialog.addEventListener("close", () => verifying?.trigger.isConnected && verifying.trigger.focus());
  clearErrorOnInput(verifyCode);

  verifyForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (confirming || !verifying) return;
    hideAlert(verifyAlert);
    if (!validateFields([[verifyCode, (value) => (/^[0-9]{6}$/.test(value.trim()) ? "" : "Enter the 6-digit code.")]])) return;
    confirming = true;
    setBusy(verifyConfirm, true, "Checking…");
    try {
      const updated = await api.confirmContactVerification(await getToken(), verifying.contact.id, verifyCode.value.trim());
      contacts = contacts.map((c) => (c.id === updated.id ? updated : c));
      verifyDialog.close();
      render();
    } catch (error) {
      const shown = error instanceof ApiError && showServerFieldErrors(error.details, { code: verifyCode });
      if (!shown) fail(error, "We couldn't check that code. Please try again.", verifyAlert);
    } finally {
      confirming = false;
      setBusy(verifyConfirm, false);
    }
  });

  /* ---------- remove ---------- */

  const confirmDialog = $("confirm-dialog");
  const confirmForm = $("confirm-form");
  const confirmOk = $("confirm-ok");
  let pendingRemoval = null;
  let removing = false;

  function askToRemove(contact, trigger) {
    pendingRemoval = { contact, trigger };
    $("confirm-text").textContent = `${contact.name} will no longer appear in your emergency contacts.`;
    confirmDialog.showModal();
  }

  $("confirm-cancel").addEventListener("click", () => confirmDialog.close());
  confirmDialog.addEventListener("click", (event) => {
    if (event.target === confirmDialog) confirmDialog.close();
  });
  confirmDialog.addEventListener("close", () => {
    if (!removing && pendingRemoval?.trigger.isConnected) pendingRemoval.trigger.focus();
  });

  confirmForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (removing || !pendingRemoval) return;
    removing = true;
    setBusy(confirmOk, true, "Removing…");
    const { contact } = pendingRemoval;
    try {
      await api.deleteContact(await getToken(), contact.id);
      contacts = contacts.filter((c) => c.id !== contact.id);
      confirmDialog.close();
      render();
      hideAlert(alertBox);
      (list.querySelector("button") ?? nameInput).focus();
    } catch (error) {
      confirmDialog.close();
      if (error instanceof ApiError && error.status === 404) {
        contacts = contacts.filter((c) => c.id !== contact.id); // already gone elsewhere
        render();
      } else {
        fail(error, "We couldn't remove that contact. Please try again.");
      }
    } finally {
      removing = false;
      pendingRemoval = null;
      setBusy(confirmOk, false);
    }
  });

  load();
  return { getContacts: () => contacts };
}
