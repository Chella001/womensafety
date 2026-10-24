/** Overview: counts, 14-day trends and recent activity, all from GET /api/admin/dashboard. */

import { api } from "./api.js";
import { $, alertPill, element, formatDateTime, getToken, notificationPill, outcomePill, report, short, utcOffsetMinutes } from "./admin-common.js";

function renderBars(containerId, points, noun) {
  const container = $(containerId);
  const max = Math.max(1, ...points.map((p) => p.count));
  const total = points.reduce((sum, p) => sum + p.count, 0);
  container.setAttribute("role", "img");
  container.setAttribute("aria-label", `${noun} per day over the last ${points.length} days: ${total} in total.`);
  container.replaceChildren(
    ...points.map((point) => {
      const column = element("div", "bars__col");
      column.title = `${point.date}: ${point.count}`;
      const bar = element("div", "bars__bar");
      bar.style.height = `${Math.max(point.count === 0 ? 2 : 8, Math.round((point.count / max) * 100))}%`;
      if (point.count === 0) bar.classList.add("bars__bar--zero");
      column.append(bar, element("span", "bars__label", point.date.slice(8)));
      return column;
    }),
  );
  $(`${containerId}-total`).textContent = `${total} in the last ${points.length} days`;
}

export async function load() {
  $("overview-loading").hidden = false;
  $("overview-body").hidden = true;
  try {
    const d = await api.admin.dashboard(await getToken(), utcOffsetMinutes());
    const set = (id, value) => {
      $(id).textContent = String(value);
    };
    set("stat-total", d.totalUsers);
    set("stat-active", d.activeUsers);
    set("stat-suspended", d.suspendedUsers);
    set("stat-verified", d.verifiedUsers);
    set("stat-alerts", d.totalAlerts);
    set("stat-new", d.alertsNew);
    set("stat-ack", d.alertsAcknowledged);
    set("stat-resolved", d.alertsResolved);
    set("stat-cancelled", d.alertsCancelled);
    set("stat-24h", d.alertsLast24Hours);
    set("stat-reports", d.openIncidentReports);

    renderBars("chart-registrations", d.registrationsByDay, "Registrations");
    renderBars("chart-alerts", d.alertsByDay, "SOS records");

    $("recent-alerts").replaceChildren(
      ...d.recentAlerts.map((alert) => {
        const item = element("li", "record");
        const head = element("div", "record__head");
        head.append(element("span", "", `User ${alert.userRef}`), alertPill(alert.status));
        const meta = element("p", "record__meta", `${formatDateTime(alert.createdAt)} · messages: `);
        meta.append(notificationPill(alert.notification.state));
        item.append(head, meta);
        return item;
      }),
    );
    $("recent-alerts-empty").hidden = d.recentAlerts.length !== 0;
    $("recent-audit").replaceChildren(
      ...d.recentAuditLogs.map((entry) => {
        const item = element("li", "record");
        const head = element("div", "record__head");
        head.append(element("span", "", entry.action), outcomePill(entry.outcome));
        item.append(head, element("p", "record__meta", formatDateTime(entry.createdAt)));
        return item;
      }),
    );
    $("recent-audit-empty").hidden = d.recentAuditLogs.length !== 0;
    $("overview-body").hidden = false;
  } catch (error) {
    report(error, "We couldn't load the overview.");
  } finally {
    $("overview-loading").hidden = true;
  }
}
