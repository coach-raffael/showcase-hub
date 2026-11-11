-- Showcase-Hub: Registry, Versionen mit Qualitätsgate, Lebenszyklus-Ereignisse, Katalog, MCP-Richtlinie, Audit.

-- Slugs, die der Hub selbst unter demos.coach-raffael.com belegt
create function is_reserved_slug(s text) returns boolean language sql immutable as $$
  select s in ('api', 'admin', 'auth', '_next', 'static', 'assets', 'health', 'catalog', 'demos',
               'favicon.ico', 'robots.txt', 'sitemap.xml', 'well-known', 'public', 'mcp')
$$;

create table demos (
    id              bigserial primary key,
    slug            text not null unique
                    check (slug ~ '^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$' and not is_reserved_slug(slug)),
    -- managed: Hub deployt und steuert; external: nur Katalogeintrag (z. B. langtutor.cloud)
    kind            text not null check (kind in ('managed', 'external')),
    source_repo     text check (source_repo ~ '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'),
    source_path     text not null default 'demo.yaml',
    external_url    text check (external_url ~ '^https://'),
    -- Zuletzt gelesenes demo.yaml (Text) und seine geprüfte Form
    manifest_yaml   text,
    manifest        jsonb,

    -- Katalog
    title           jsonb not null,
    teaser          jsonb not null default '{}',
    description     jsonb not null default '{}',
    category        text not null default 'showcase' check (category in ('showcase', 'demo', 'tool')),
    tags            text[] not null default '{}',
    visibility      text not null default 'private' check (visibility in ('public', 'unlisted', 'private')),
    sort_order      int not null default 100,
    featured        boolean not null default false,
    screenshot_url  text,

    -- Betrieb
    lifecycle       text not null default 'on-demand' check (lifecycle in ('permanent', 'on-demand', 'ttl')),
    ttl_days        int check (ttl_days between 1 and 365),
    expires_at      timestamptz,
    routing         text not null default 'path' check (routing in ('path', 'subdomain')),
    idle_minutes    int not null default 60 check (idle_minutes between 5 and 1440),
    -- Wer eine schlafende Demo wecken darf
    wake_policy     text not null default 'anyone' check (wake_policy in ('anyone', 'share_link', 'admin')),
    auto_deploy     boolean not null default true,
    state           text not null default 'new'
                    check (state in ('new', 'deploying', 'running', 'stopped', 'failed', 'expired')),
    state_at        timestamptz not null default now(),
    last_woken_at   timestamptz,
    current_version_id bigint,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    check (kind = 'external' or source_repo is not null),
    check (kind = 'managed' or external_url is not null),
    check (lifecycle <> 'ttl' or ttl_days is not null)
);

-- Jede gebaute Version eines Showcases mit Image-Referenzen und Ergebnis des Qualitätsgates.
-- Nur anfügbar – Gate-Ergebnisse sind Nachweise und werden nie umgeschrieben (Ergänzungen per gate_experience).
create table demo_versions (
    id              bigserial primary key,
    demo_id         bigint not null references demos (id),
    git_sha         text not null check (git_sha ~ '^[0-9a-f]{7,40}$'),
    images          jsonb not null,          -- {"web": "ghcr.io/...@sha256:..."}
    gate            jsonb not null,          -- {"checks": [{"name","ok","detail"}]} aus build-showcase
    gate_passed     boolean not null,
    -- Prüfungen gegen die laufende Demo (Playwright, Lighthouse) nach dem ersten Deploy
    gate_experience jsonb,
    experience_passed boolean,
    run_url         text,
    created_at      timestamptz not null default now(),
    unique (demo_id, git_sha)
);
alter table demos add constraint demos_current_version_fk foreign key (current_version_id) references demo_versions (id);

create function protect_demo_version() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'Versionen sind Nachweise und werden nicht gelöscht';
  end if;
  if new.git_sha <> old.git_sha or new.images <> old.images or new.gate <> old.gate
     or new.gate_passed <> old.gate_passed or new.demo_id <> old.demo_id then
    raise exception 'Build-Ergebnis einer Version ist unveränderlich';
  end if;
  if old.gate_experience is not null and (new.gate_experience is distinct from old.gate_experience
     or new.experience_passed is distinct from old.experience_passed) then
    raise exception 'Erlebnis-Prüfung ist bereits festgeschrieben';
  end if;
  return new;
end $$;
create trigger demo_versions_protect before update or delete on demo_versions
  for each row execute function protect_demo_version();

-- Werte für env_required, verschlüsselt (AES-GCM, Schlüssel HUB_SECRET_KEY)
create table demo_secrets (
    demo_id     bigint not null references demos (id) on delete cascade,
    name        text not null check (name ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    value_enc   text not null,
    updated_at  timestamptz not null default now(),
    primary key (demo_id, name)
);

-- Start/Stopp/Deploy/Reset – wer, wann, über welchen Kanal, mit welchem Ergebnis
create table demo_events (
    id          bigserial primary key,
    demo_id     bigint not null references demos (id),
    action      text not null,
    actor       text not null,
    channel     text not null,
    ok          boolean not null,
    detail      jsonb,
    at          timestamptz not null default now()
);
create index demo_events_demo_at on demo_events (demo_id, at desc);

-- Share-Links (Phase 4 nutzt sie für Leads; Wecken funktioniert schon damit)
create table share_links (
    id          bigserial primary key,
    demo_id     bigint not null references demos (id),
    token_hash  text not null unique,
    label       text,
    lead_ref    text,
    expires_at  timestamptz,
    created_by  text not null,
    created_at  timestamptz not null default now(),
    last_used_at timestamptz,
    uses        int not null default 0
);

-- MCP-Richtlinie je Tool (wie im CRM)
create table mcp_policy (
    tool        text primary key,
    mode        text not null check (mode in ('ALLOW', 'APPROVAL', 'DENY')),
    locked      boolean not null default false,
    description text not null,
    updated_at  timestamptz not null default now(),
    updated_by  text not null default 'seed'
);

insert into mcp_policy (tool, mode, locked, description) values
  ('read',            'ALLOW',    false, 'Demos, Status, Logs, Gate-Berichte, Ereignisse lesen'),
  ('register_demo',   'ALLOW',    false, 'Showcase aus einem Repo registrieren bzw. demo.yaml neu einlesen'),
  ('control_demo',    'ALLOW',    false, 'Demo starten, stoppen, neu starten'),
  ('deploy_demo',     'ALLOW',    false, 'Version ausrollen (nur Versionen mit grünem Gate, wenn öffentlich)'),
  ('reset_demo',      'ALLOW',    false, 'Demo-Daten auf den Ausgangszustand zurücksetzen'),
  ('update_catalog',  'ALLOW',    false, 'Katalogtexte, Tags, Reihenfolge ändern'),
  ('set_lifecycle',   'ALLOW',    false, 'Lebenszyklus, TTL, Leerlaufzeit, Wecken ändern'),
  ('create_share_link','ALLOW',   false, 'Share-Link erzeugen'),
  ('set_visibility',  'APPROVAL', false, 'Sichtbarkeit ändern (öffentlich nur bei grünem Gate)'),
  ('change_routing',  'APPROVAL', false, 'Routing Pfad ↔ Subdomain umstellen'),
  ('delete_demo',     'DENY',     true,  'Demo entfernen – nur in der Oberfläche'),
  ('manage_policy',   'DENY',     true,  'Richtlinie ändern – nur in der Oberfläche');

create table pending_actions (
    id              bigserial primary key,
    tool            text not null references mcp_policy (tool),
    arguments       jsonb not null,
    summary         text,
    idempotency_key text unique,
    status          text not null default 'pending'
                    check (status in ('pending', 'approved', 'rejected', 'executed', 'failed')),
    requested_at    timestamptz not null default now(),
    requested_by    text not null,
    decided_at      timestamptz,
    decided_by      text,
    result          jsonb
);

-- Audit: nur anfügbar
create table audit_log (
    id          bigserial primary key,
    at          timestamptz not null default now(),
    actor       text not null,
    channel     text not null check (channel in ('ui', 'mcp', 'system', 'ci', 'automation')),
    action      text not null,
    entity_type text not null,
    entity_id   bigint,
    before      jsonb,
    after       jsonb
);
create function audit_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'audit_log ist nur anfügbar';
end $$;
create trigger audit_log_no_update before update or delete on audit_log
  for each statement execute function audit_append_only();
create trigger audit_log_no_truncate before truncate on audit_log
  for each statement execute function audit_append_only();

-- Langtutor als erster externer Katalogeintrag
insert into demos (slug, kind, external_url, title, teaser, category, tags, visibility, sort_order, state)
values ('langtutor', 'external', 'https://langtutor.cloud',
        '{"en": "Langtutor", "de": "Langtutor"}',
        '{"en": "Learn Georgian the right way: grammar explained, spaced repetition, native-script audio.",
          "de": "Georgisch richtig lernen: Grammatik erklärt, Spaced Repetition, Audio in Originalschrift."}',
        'tool', '{spring-boot,nextjs,fsrs,keycloak}', 'public', 10, 'running');
