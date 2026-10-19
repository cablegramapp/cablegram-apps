import qrcode from "qrcode-generator";

/** What the pairing QR holds: a web address any phone camera can open. Same shape as the Android TV app's pairLink. */
export function pairLink(webAppBase: string, pin: string, tvName: string): string {
  return `${webAppBase.replace(/\/+$/, "")}/?pair=${pin}&tv=${encodeURIComponent(tvName)}`;
}

/** Draws [text] as a QR code on a canvas, with a white quiet zone so phone cameras read it on a dark screen. */
export function qrCanvas(text: string, size = 360): HTMLCanvasElement {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const quiet = 2;
  const cell = Math.floor(size / (n + quiet * 2));
  const side = cell * (n + quiet * 2);
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = side;
  const ctx = canvas.getContext("2d")!;
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, side, side);
  ctx.fillStyle = "#000";
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    if (qr.isDark(r, c)) ctx.fillRect((c + quiet) * cell, (r + quiet) * cell, cell, cell);
  }
  return canvas;
}
