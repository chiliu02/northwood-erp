// Persona metadata mirrored from user-stories.md and demo-web-ui-design.md.
// The accent colour is identity-only — never used for status — and matches
// the --color-persona-* CSS custom properties in index.css.

export type PersonaKey =
  | "emma" | "sarah" | "mike" | "linda" | "tom" | "olivia" | "daniel";

export interface Persona {
  key: PersonaKey;
  name: string;
  role: string;
  accentVar: string;          // CSS variable, e.g. var(--color-persona-emma)
}

export const PERSONAS: Record<PersonaKey, Persona> = {
  emma:   { key: "emma",   name: "Emma",   role: "Catalog",     accentVar: "var(--color-persona-emma)"   },
  sarah:  { key: "sarah",  name: "Sarah",  role: "Sales",       accentVar: "var(--color-persona-sarah)"  },
  mike:   { key: "mike",   name: "Mike",   role: "Inventory",   accentVar: "var(--color-persona-mike)"   },
  linda:  { key: "linda",  name: "Linda",  role: "Production",  accentVar: "var(--color-persona-linda)"  },
  tom:    { key: "tom",    name: "Tom",    role: "Purchasing",  accentVar: "var(--color-persona-tom)"    },
  olivia: { key: "olivia", name: "Olivia", role: "AR / AP",     accentVar: "var(--color-persona-olivia)" },
  daniel: { key: "daniel", name: "Daniel", role: "Finance",     accentVar: "var(--color-persona-daniel)" },
};

export const PERSONA_ORDER: PersonaKey[] = [
  "emma", "sarah", "mike", "linda", "tom", "olivia", "daniel",
];
