# Upgrade from 1.x

1. Stop the server and copy `plugins/OrderHub` somewhere safe.
2. Replace the jar by 2.0.0 and start. The database is migrated (V5) after an automatic backup in `plugins/OrderHub/backups/pre-migration-v*.db`. The migration is refused if the backup fails.
3. `config.yml` gets the new options (`config-version` 3). Your values stay, the old file is kept as `config.yml.bak`.
4. All language files are bundled in the jar. A `messages_<language>.yml` in `plugins/OrderHub` takes precedence, keys it lacks fall back to the bundled text.
5. Check `/orderadmin health`.

Behavior changes: Vault calls have a timeout (default 3 seconds), potions, banners, fireworks, goat horns, ominous bottles, arrows and player heads are accepted by default (`items.component-presets: []` turns that off). Rolling back to 1.1.0 needs the backup from step 2, the migrated database is not supported by 1.1.0.
