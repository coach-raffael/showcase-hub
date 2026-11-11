// Kleine Anzeige-Helfer (Server und Client).

export function pick(map: Record<string, string> | undefined, lang = "en"): string {
  if (!map) return "";
  return map[lang] ?? map.en ?? Object.values(map)[0] ?? "";
}

const dateTime = new Intl.DateTimeFormat("de-DE", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Tbilisi",
});

export function when(iso?: string | null): string {
  if (!iso) return "–";
  return dateTime.format(new Date(iso));
}

export function short(sha?: string | null): string {
  return sha ? sha.slice(0, 7) : "–";
}

export const STATE_LABEL: Record<string, string> = {
  new: "neu",
  deploying: "wird ausgerollt",
  running: "läuft",
  stopped: "schläft",
  failed: "Fehler",
  expired: "abgelaufen",
};

export const VISIBILITY_LABEL: Record<string, string> = {
  public: "öffentlich",
  unlisted: "nur per Link",
  private: "privat",
};

export const LIFECYCLE_LABEL: Record<string, string> = {
  permanent: "permanent",
  "on-demand": "bei Bedarf",
  ttl: "befristet",
};
