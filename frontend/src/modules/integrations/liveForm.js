/**
 * The live connector form, derived only from what the connector declares (task 24.7, spec
 * admin/integrations-connectors "Generic live connector screens"): `configSchema.fields` and
 * `secretSpec` from GET /integrations/:tool/connection. Nothing here knows a tool.
 *
 * A config field is `{name, label, type, required, pattern, help, options, dependsOn, default}` with
 * type text, url, list, date, boolean or choice. `dependsOn: {field, value | values}` shows the field
 * only while that other field holds one of the values; a hidden field is not sent.
 */

/** HTML input type of a config field type (list and choice are rendered by LiveFields itself). */
export function liveInputType(type) {
  return { url: 'url', date: 'date' }[type] ?? 'text';
}

/** `[{value, label}]` of a choice field; options may be plain strings. */
export function choiceOptions(field) {
  return (field.options ?? []).map((o) => (typeof o === 'object' && o !== null ? { value: String(o.value), label: o.label ?? String(o.value) } : { value: String(o), label: String(o) }));
}

/** The declared config fields, or none. */
export function schemaFields(schema) {
  return Array.isArray(schema?.fields) ? schema.fields.filter((f) => f && f.name) : [];
}

/** True when the field counts with the current values (its dependsOn holds, or it has none). */
export function applies(field, values) {
  const dep = field.dependsOn;

  if (!dep?.field) {
    return true;
  }

  const wanted = Array.isArray(dep.values) ? dep.values : [dep.value];
  const current = values[dep.field];

  return current != null && wanted.some((v) => v != null && String(v) === String(current).trim());
}

/** The fields to show now, in schema order. */
export function visibleFields(schema, values) {
  return schemaFields(schema).filter((f) => applies(f, values));
}

/** A list value as the text of its input: "PUL, OPS". */
function listText(value) {
  return Array.isArray(value) ? value.join(', ') : (value ?? '');
}

/**
 * Initial form values: the stored config where there is one, else each field's default. Lists are
 * edited as comma-separated text, booleans as true/false; the secret is never part of these.
 */
export function initialValues(schema, stored) {
  const out = {};
  const config = stored ?? {};

  schemaFields(schema).forEach((f) => {
    const has = Object.prototype.hasOwnProperty.call(config, f.name);
    const value = has ? config[f.name] : f.default;

    if (f.type === 'boolean') {
      out[f.name] = value === true;
    } else if (f.type === 'list') {
      out[f.name] = listText(value);
    } else {
      out[f.name] = value == null ? '' : String(value);
    }
  });

  return out;
}

/** Splits a comma-separated list into trimmed, non-empty, distinct items. */
export function splitList(text) {
  return [...new Set(String(text ?? '').split(',').map((s) => s.trim()).filter(Boolean))];
}

/**
 * Client-side checks before Test connection: required fields and declared patterns. The server
 * checks everything again (LiveConfig); these only save a round trip. Keys are `config.<name>` and
 * `secret`, the same as the server's error fields.
 */
export function validate(schema, secretSpec, values, secret) {
  const errors = {};

  visibleFields(schema, values).forEach((f) => {
    const key = `config.${f.name}`;
    const raw = values[f.name];

    if (f.type === 'boolean') {
      return;
    }

    const items = f.type === 'list' ? splitList(raw) : [String(raw ?? '').trim()].filter(Boolean);

    if (!items.length) {
      if (f.required) {
        errors[key] = `${f.label ?? f.name} is required.`;
      }

      return;
    }

    if (f.pattern && (f.type === 'text' || f.type === 'list')) {
      let re = null;

      try {
        re = new RegExp(`^(?:${f.pattern})$`);
      } catch {
        // a pattern JavaScript cannot read is left to the server
      }

      if (re && items.some((v) => !re.test(v))) {
        errors[key] = `${f.label ?? f.name} has the wrong format.`;
      }
    }
  });

  if (!String(secret ?? '').trim()) {
    errors.secret = `Enter the ${String(secretSpec?.label ?? 'secret').toLowerCase()}.`;
  }

  return errors;
}

/** The `config` to send: visible fields only, lists as arrays, empty text left out. */
export function configBody(schema, values) {
  const out = {};

  visibleFields(schema, values).forEach((f) => {
    const raw = values[f.name];

    if (f.type === 'boolean') {
      out[f.name] = raw === true;
    } else if (f.type === 'list') {
      const items = splitList(raw);

      if (items.length) {
        out[f.name] = items;
      }
    } else {
      const v = String(raw ?? '').trim();

      if (v) {
        out[f.name] = v;
      }
    }
  });

  return out;
}

/** The account a test signed in as, as one line: a login, or the parts of an account object. */
export function accountText(account) {
  if (account == null || account === '') {
    return '';
  }

  if (typeof account !== 'object') {
    return String(account);
  }

  const name = account.displayName ?? account.name;
  const login = account.login ?? account.username;
  const parts = [name, login && login !== name ? login : null, account.email].filter(Boolean);

  return parts.length ? parts.join(' · ') : JSON.stringify(account);
}

/** A warning from the server: `{field, code, message}` or a plain string. */
export function warningText(w) {
  return typeof w === 'string' ? w : (w?.message ?? '');
}

/**
 * Proposed values from a test (`fields`), for the config fields the schema declares: each is
 * `{field, label, candidates: [{value, label}]}`, best first. A proposal may be one value, a list of
 * values, or `[{id, name}]`.
 */
export function proposals(schema, fields) {
  if (!fields || typeof fields !== 'object') {
    return [];
  }

  return schemaFields(schema)
    .filter((f) => fields[f.name] != null)
    .map((f) => {
      const raw = Array.isArray(fields[f.name]) ? fields[f.name] : [fields[f.name]];
      const candidates = raw
        .map((c) => {
          if (c && typeof c === 'object') {
            const value = c.id ?? c.value;

            return value == null ? null : { value: String(value), label: c.name && c.name !== value ? `${c.name} (${value})` : String(value) };
          }

          return c == null || c === '' ? null : { value: String(c), label: String(c) };
        })
        .filter(Boolean);

      return { field: f.name, label: f.label ?? f.name, candidates };
    })
    .filter((p) => p.candidates.length);
}
