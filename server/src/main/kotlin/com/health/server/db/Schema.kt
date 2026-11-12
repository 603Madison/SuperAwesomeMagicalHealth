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
