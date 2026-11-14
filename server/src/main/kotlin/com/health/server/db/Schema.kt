package com.health.server.db

/**
 * The database's tables, as a list of migrations. Each runs once, in order, and the database
 * remembers how many have run (SQLite's user_version); never edit one that has been released,
 * add another.
 */
object Schema {
    private val migrations: List<String> = listOf(
        // 1: accounts, sign-ins, settings; devices and what they recorded; food; check-ins.
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            display_name TEXT NOT NULL,
            password_hash TEXT,
            role TEXT NOT NULL,
            break_glass INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0,
            oidc_issuer TEXT,
            oidc_subject TEXT,
            created_at INTEGER NOT NULL,
            last_login_at INTEGER,
            UNIQUE (oidc_issuer, oidc_subject)
        );
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX sessions_user ON sessions(user_id);
        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE profiles (
            user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            data TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE devices (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            identifier TEXT NOT NULL,
            name TEXT NOT NULL,
            model TEXT,
            firmware TEXT,
            battery INTEGER,
            last_sync_at INTEGER,
            created_at INTEGER NOT NULL,
            UNIQUE (user_id, kind, identifier)
        );
        CREATE TABLE samples (
            id INTEGER PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            metric TEXT NOT NULL,
            start INTEGER NOT NULL,
            end INTEGER,
            value REAL NOT NULL,
            UNIQUE (user_id, device_id, metric, start)
        );
        CREATE INDEX samples_metric ON samples(user_id, metric, start);
        CREATE INDEX samples_device ON samples(device_id);
        CREATE TABLE sleep_sessions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            start INTEGER NOT NULL,
            end INTEGER NOT NULL,
            stages TEXT NOT NULL DEFAULT '[]',
            manual INTEGER NOT NULL DEFAULT 0,
            note TEXT,
            UNIQUE (user_id, device_id, start)
        );
        CREATE INDEX sleep_user_end ON sleep_sessions(user_id, end);
        CREATE TABLE workouts (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            type TEXT NOT NULL,
            start INTEGER NOT NULL,
            end INTEGER NOT NULL,
            name TEXT,
            distance_m REAL,
            active_calories REAL,
            avg_hr REAL,
            max_hr REAL,
            steps INTEGER,
            effort INTEGER,
            note TEXT,
            manual INTEGER NOT NULL DEFAULT 0,
            UNIQUE (user_id, device_id, start)
        );
        CREATE INDEX workouts_user_start ON workouts(user_id, start);
        CREATE TABLE foods (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            brand TEXT,
            barcode TEXT,
            serving TEXT NOT NULL,
            serving_grams REAL,
            nutrients TEXT NOT NULL,
            source TEXT NOT NULL,
            uses INTEGER NOT NULL DEFAULT 0,
            last_used_at INTEGER,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX foods_user ON foods(user_id, last_used_at);
        CREATE INDEX foods_barcode ON foods(user_id, barcode);
        CREATE TABLE food_entries (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            meal TEXT NOT NULL,
            name TEXT NOT NULL,
            brand TEXT,
            servings REAL NOT NULL,
            serving TEXT NOT NULL,
            food_id TEXT,
            nutrients TEXT NOT NULL,
            logged_at INTEGER NOT NULL
        );
        CREATE INDEX food_entries_day ON food_entries(user_id, date);
        CREATE TABLE water (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            ml INTEGER NOT NULL,
            at INTEGER NOT NULL
        );
        CREATE INDEX water_day ON water(user_id, date);
        CREATE TABLE checkins (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            data TEXT NOT NULL,
            PRIMARY KEY (user_id, date)
        );
        CREATE TABLE day_cache (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            data TEXT NOT NULL,
            PRIMARY KEY (user_id, date)
        );
        CREATE TABLE food_lookups (
            key TEXT PRIMARY KEY,
            data TEXT NOT NULL,
            fetched_at INTEGER NOT NULL
        );
        """,
        // 2: the health modules. Their entries are sealed (encrypted) JSON; connector links get a scope.
        """
        ALTER TABLE sessions ADD COLUMN scope TEXT;
        CREATE TABLE entries (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            date TEXT NOT NULL,
            at INTEGER NOT NULL,
            data TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX entries_kind_date ON entries(user_id, kind, date);
        CREATE TABLE medications (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            data TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX medications_user ON medications(user_id);
        """,
        // 3: blood pressure and glucose, drinks, habits, weather, sharing, webhooks, browser
        // notifications, reminders sent, weekly digests. (Symptoms, hormone therapy, life stages
        // and appointments are sealed entries, in `entries`.)
        """
        CREATE TABLE vitals (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            at INTEGER NOT NULL,
            date TEXT NOT NULL,
            v1 REAL NOT NULL,
            v2 REAL,
            v3 REAL,
            context TEXT,
            note TEXT,
            device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX vitals_user ON vitals(user_id, kind, at);
        CREATE UNIQUE INDEX vitals_device ON vitals(device_id, kind, at) WHERE device_id IS NOT NULL;
        CREATE TABLE drinks (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            at INTEGER NOT NULL,
            kind TEXT NOT NULL,
            volume_ml INTEGER NOT NULL,
            caffeine_mg REAL NOT NULL DEFAULT 0,
            alcohol_g REAL NOT NULL DEFAULT 0,
            name TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX drinks_user ON drinks(user_id, date);
        CREATE TABLE habits (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            data TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            sort INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX habits_user ON habits(user_id);
        CREATE TABLE habit_checks (
            habit_id TEXT NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            count INTEGER NOT NULL,
            PRIMARY KEY (habit_id, date)
        );
        CREATE TABLE weather (
            place TEXT NOT NULL,
            date TEXT NOT NULL,
            data TEXT NOT NULL,
            fetched_at INTEGER NOT NULL,
            PRIMARY KEY (place, date)
        );
        CREATE TABLE shares (
            id TEXT PRIMARY KEY,
            owner_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            viewer_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            modules TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_viewed_at INTEGER,
            UNIQUE (owner_id, viewer_id)
        );
        CREATE INDEX shares_viewer ON shares(viewer_id);
        CREATE TABLE webhooks (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            secret TEXT NOT NULL,
            events TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            include_details INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX webhooks_user ON webhooks(user_id);
        CREATE TABLE webhook_deliveries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            webhook_id TEXT NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            at INTEGER NOT NULL,
            event TEXT NOT NULL,
            ok INTEGER NOT NULL,
            status INTEGER,
            error TEXT
        );
        CREATE INDEX webhook_deliveries_hook ON webhook_deliveries(webhook_id, at);
        CREATE TABLE push_subscriptions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            endpoint TEXT NOT NULL UNIQUE,
            p256dh TEXT NOT NULL,
            auth TEXT NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_ok_at INTEGER,
            failures INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX push_user ON push_subscriptions(user_id);
        CREATE TABLE reminders_sent (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            at INTEGER NOT NULL,
            PRIMARY KEY (user_id, key)
        );
        CREATE TABLE digests (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            week TEXT NOT NULL,
            data TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            emailed_at INTEGER,
            PRIMARY KEY (user_id, week)
        );
        """,
        // 4: per-person settings (conditions, feature settings, AI consent: sealed), supplies (sealed),
        // progress photos (files sealed in the data folder), the AI assistant's audit log.
        """
        CREATE TABLE user_settings (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            data TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (user_id, key)
        );
        CREATE TABLE supplies (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            data TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX supplies_user ON supplies(user_id);
        CREATE TABLE photos (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            date TEXT NOT NULL,
            at INTEGER NOT NULL,
            file TEXT NOT NULL,
            thumb TEXT NOT NULL,
            bytes INTEGER NOT NULL,
            data TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX photos_user ON photos(user_id, at);
        CREATE TABLE ai_audit (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            at INTEGER NOT NULL,
            data TEXT NOT NULL
        );
        CREATE INDEX ai_audit_user ON ai_audit(user_id, at);
        """,
        // Where a food came from, to know it again: "mealie:<recipe id>".
        """
        ALTER TABLE foods ADD COLUMN ref TEXT;
        CREATE INDEX foods_ref ON foods(user_id, ref);
        """,
    )

    fun migrate(db: Database) = db.blockingTx {
        val current = long("PRAGMA user_version").toInt()
        require(current <= migrations.size) {
            "This database was made by a newer SuperAwesomeMagicalHealth server (schema $current); update the server"
        }
        migrations.drop(current).forEach { migration ->
            migration.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(::execute)
        }
        execute("PRAGMA user_version = ${migrations.size}")
    }
}
