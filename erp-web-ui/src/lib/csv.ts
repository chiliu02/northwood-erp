/**
 * Download an array of flat row objects as a CSV file. The header row is the
 * keys of the first object; values are RFC-4180-escaped, nested objects are
 * JSON-encoded, null/undefined become empty cells. A UTF-8 BOM is prepended so
 * Excel opens it with the right encoding.
 *
 * Used by the list pages' Export action on the currently-filtered rows, so the
 * download mirrors what the operator sees on screen. No-op on an empty set.
 */
export function downloadCsv<T extends object>(filename: string, rows: T[]): void {
  if (rows.length === 0) return;
  const records = rows as ReadonlyArray<Record<string, unknown>>;
  const headers = Object.keys(records[0]);
  const escape = (value: unknown): string => {
    if (value == null) return "";
    const s = typeof value === "object" ? JSON.stringify(value) : String(value);
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const csv = [
    headers.join(","),
    ...records.map((row) => headers.map((h) => escape(row[h])).join(",")),
  ].join("\r\n");

  const BOM = "﻿";
  const blob = new Blob([BOM + csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
