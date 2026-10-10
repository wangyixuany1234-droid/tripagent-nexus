function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function formatValue(value) {
  if (value === undefined || value === null) {
    return "-";
  }
  if (typeof value === "number") {
    return Number.isInteger(value) ? String(value) : value.toFixed(3);
  }
  return String(value);
}

function metricsRows(data) {
  return Object.entries(data.metrics || {})
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, metric]) => {
      const values = metric.values || {};
      const thresholds = Object.values(metric.thresholds || {});
      const thresholdState =
        thresholds.length === 0
          ? "-"
          : thresholds.every((item) => item.ok)
            ? "PASS"
            : "FAIL";
      return `<tr>
        <td>${escapeHtml(name)}</td>
        <td>${escapeHtml(formatValue(values.count ?? values.rate))}</td>
        <td>${escapeHtml(formatValue(values.med))}</td>
        <td>${escapeHtml(formatValue(values["p(90)"]))}</td>
        <td>${escapeHtml(formatValue(values["p(95)"]))}</td>
        <td>${escapeHtml(formatValue(values["p(99)"]))}</td>
        <td>${escapeHtml(thresholdState)}</td>
      </tr>`;
    })
    .join("\n");
}

function buildHtml(data, profile) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TripAgent Nexus k6 ${escapeHtml(profile)}</title>
  <style>
    body { margin: 0; color: #17202a; background: #f7f8fa; font: 14px/1.5 system-ui, sans-serif; }
    main { width: min(1180px, calc(100% - 40px)); margin: 32px auto; }
    h1 { margin: 0 0 8px; font-size: 28px; }
    p { margin: 0 0 24px; color: #52606d; }
    table { width: 100%; border-collapse: collapse; background: #fff; }
    th, td { padding: 10px 12px; border: 1px solid #d9e0e7; text-align: right; }
    th:first-child, td:first-child { text-align: left; }
    th { background: #eef2f5; }
  </style>
</head>
<body>
  <main>
    <h1>TripAgent Nexus k6 ${escapeHtml(profile)}</h1>
    <p>仅包含聚合指标，不包含请求正文、凭据或用户数据。</p>
    <table>
      <thead>
        <tr>
          <th>指标</th>
          <th>计数/比率</th>
          <th>p50</th>
          <th>p90</th>
          <th>p95</th>
          <th>p99</th>
          <th>门槛</th>
        </tr>
      </thead>
      <tbody>${metricsRows(data)}</tbody>
    </table>
  </main>
</body>
</html>`;
}

function stdoutSummary(data, profile) {
  const metrics = data.metrics || {};
  const duration = metrics.http_req_duration?.values || {};
  const requests = metrics.http_reqs?.values || {};
  const failures = metrics.http_req_failed?.values || {};
  return [
    `profile=${profile}`,
    `throughput_rps=${formatValue(requests.rate)}`,
    `p50_ms=${formatValue(duration.med)}`,
    `p90_ms=${formatValue(duration["p(90)"])}`,
    `p95_ms=${formatValue(duration["p(95)"])}`,
    `p99_ms=${formatValue(duration["p(99)"])}`,
    `http_error_rate=${formatValue(failures.rate)}`,
    "",
  ].join("\n");
}

export function summaryOutputs(data, profile) {
  const jsonPath =
    __ENV.QA_K6_JSON_PATH || `k6-${profile}-summary.json`;
  const htmlPath =
    __ENV.QA_K6_HTML_PATH || `k6-${profile}-report.html`;
  return {
    [jsonPath]: `${JSON.stringify(data, null, 2)}\n`,
    [htmlPath]: buildHtml(data, profile),
    stdout: stdoutSummary(data, profile),
  };
}
