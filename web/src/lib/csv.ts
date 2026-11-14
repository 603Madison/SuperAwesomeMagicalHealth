/*
 * CSV files to download (npm test covers these): every cell quoted, and nothing a spreadsheet
 * would run as a formula. No imports, so node can run it as it is.
 */

/**
 * One cell: quoted, with its quotes doubled and carriage returns dropped. Text that starts with
 * = + - @ (or a tab) gets a ' in front, so a spreadsheet shows it rather than running it.
 */
export function csvCell(value: unknown): string {
  let text = value == null ? "" : String(value);
  text = text.replace(/\r/g, "");
  if (/^[=+\-@\t]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

/** Rows of cells as a CSV file's text: one line each, ending with CRLF as spreadsheets expect. */
export function toCsv(rows: unknown[][]): string {
  return rows.map((cells) => cells.map(csvCell).join(",")).join("\r\n") + "\r\n";
}
