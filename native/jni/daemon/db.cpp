#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#include "magisk.h"
#include "db.h"

#define DB_VERSION 7

db_strings::db_strings() {
	memset(data, 0, sizeof(data));
}

char *db_strings::operator[](const char *key) {
	return data[getKeyIdx(key)];
}

const char *db_strings::operator[](const char *key) const {
	return data[getKeyIdx(key)];
}

char *db_strings::operator[](const int idx) {
	return data[idx];
}

const char *db_strings::operator[](const int idx) const {
	return data[idx];
}

int db_strings::getKeyIdx(const char *key) const {
	int idx = DB_STRING_NUM;
	for (int i = 0; i < DB_STRING_NUM; ++i) {
		if (strcmp(key, DB_STRING_KEYS[i]) == 0)
			idx = i;
	}
	return idx;
}

db_settings::db_settings() : data {
		ROOT_ACCESS_APPS_AND_ADB,
		MULTIUSER_MODE_OWNER_ONLY,
		NAMESPACE_MODE_REQUESTER
} {}

int &db_settings::operator[](const int idx) {
	return data[idx];
}

const int &db_settings::operator[](const int idx) const {
	return data[idx];
}

int &db_settings::operator[](const char *key) {
	return data[getKeyIdx(key)];
}

const int &db_settings::operator[](const char *key) const {
	return data[getKeyIdx(key)];
}

int db_settings::getKeyIdx(const char *key) const {
	int idx = DB_SETTINGS_NUM;
	for (int i = 0; i < DB_SETTINGS_NUM; ++i) {
		if (strcmp(key, DB_SETTING_KEYS[i]) == 0)
			idx = i;
	}
	return idx;
}

static int ver_cb(void *ver, int, char **data, char **) {
	*((int *) ver) = atoi(data[0]);
	return 0;
}

#define err_abort(err) \
if (err) { \
	LOGE("sqlite3_exec: %s\n", err); \
	sqlite3_free(err); \
	return nullptr; \
}

static sqlite3 *open_and_init_db() {
	sqlite3 *db;
	int ret = sqlite3_open(MAGISKDB, &db);
	if (ret) {
		LOGE("sqlite3 open failure: %s\n", sqlite3_errstr(ret));
		return nullptr;
	}
	int ver, upgrade = 0;
	char *err;
	sqlite3_exec(db, "PRAGMA user_version", ver_cb, &ver, &err);
	err_abort(err);
	if (ver > DB_VERSION) {
		// Don't support downgrading database
		sqlite3_close_v2(db);
		return nullptr;
	}
	if (ver < 3) {
		// Policies
		sqlite3_exec(db,
					 "CREATE TABLE IF NOT EXISTS policies "
					 "(uid INT, package_name TEXT, policy INT, until INT, "
					 "logging INT, notification INT, PRIMARY KEY(uid))",
					 nullptr, nullptr, &err);
		err_abort(err);
		// Logs
		sqlite3_exec(db,
					 "CREATE TABLE IF NOT EXISTS logs "
					 "(from_uid INT, package_name TEXT, app_name TEXT, from_pid INT, "
					 "to_uid INT, action INT, time INT, command TEXT)",
					 nullptr, nullptr, &err);
		err_abort(err);
		// Settings
		sqlite3_exec(db,
					 "CREATE TABLE IF NOT EXISTS settings "
					 "(key TEXT, value INT, PRIMARY KEY(key))",
					 nullptr, nullptr, &err);
		err_abort(err);
		ver = 3;
		upgrade = 1;
	}
	if (ver == 3) {
		// Strings
		sqlite3_exec(db,
					 "CREATE TABLE IF NOT EXISTS strings "
					 "(key TEXT, value TEXT, PRIMARY KEY(key))",
					 nullptr, nullptr, &err);
		err_abort(err);
		ver = 4;
		upgrade = 1;
	}
	if (ver == 4) {
		sqlite3_exec(db, "UPDATE policies SET uid=uid%100000", nullptr, nullptr, &err);
		err_abort(err);
		/* Skip version 5 */
		ver = 6;
		upgrade = 1;
	}
	if (ver == 5 || ver == 6) {
		// Hide list
		sqlite3_exec(db,
					 "CREATE TABLE IF NOT EXISTS hidelist "
					 "(process TEXT, PRIMARY KEY(process))",
					 nullptr, nullptr, &err);
		err_abort(err);
		ver = 7;
		upgrade =1 ;
	}

	if (upgrade) {
		// Set version
		char query[32];
		sprintf(query, "PRAGMA user_version=%d", ver);
		sqlite3_exec(db, query, nullptr, nullptr, &err);
		err_abort(err);
	}
	return db;
}

sqlite3 *get_magiskdb() {
	sqlite3 *db = open_and_init_db();
	if (db == nullptr) {
		// Open fails, remove and reconstruct
		unlink(MAGISKDB);
		db = open_and_init_db();
	}
	return db;
}

static int settings_cb(void *v, int col_num, char **data, char **col_name) {
	auto &cfg = *(db_settings *) v;
	int value = -1;
	const char *key = "";
	for (int i = 0; i < col_num; ++i) {
		if (strcmp(col_name[i], "key") == 0) {
			key = data[i];
		} else if (strcmp(col_name[i], "value") == 0) {
			value = atoi(data[i]);
		}
	}
	if (key[0] && value >= 0) {
		cfg[key] = value;
		LOGD("magiskdb: query %s=[%d]\n", key, value);
	}
	return 0;
}

int get_db_settings(sqlite3 *db, struct db_settings *dbs, int key) {
	if (db == nullptr)
		return 1;
	char *err;
	if (key > 0) {
		char query[128];
		sprintf(query, "SELECT key, value FROM settings WHERE key='%s'", DB_SETTING_KEYS[key]);
		sqlite3_exec(db, query, settings_cb, dbs, &err);
	} else {
		sqlite3_exec(db, "SELECT key, value FROM settings", settings_cb, dbs, &err);
	}
	if (err) {
		LOGE("sqlite3_exec: %s\n", err);
		sqlite3_free(err);
		return 1;
	}
	return 0;
}

static int strings_cb(void *v, int col_num, char **data, char **col_name) {
	auto &str = *(db_strings *) v;
	const char *key = "", *value = "";
	for (int i = 0; i < col_num; ++i) {
		if (strcmp(col_name[i], "key") == 0) {
			key = data[i];
		} else if (strcmp(col_name[i], "value") == 0) {
			value = data[i];
		}
	}
	if (key[0] && value[0]) {
		strcpy(str[key], value);
		LOGD("magiskdb: query %s=[%s]\n", key, value);
	}
	return 0;
}

int get_db_strings(sqlite3 *db, struct db_strings *str, int key) {
	if (db == nullptr)
		return 1;
	char *err;
	if (key > 0) {
		char query[128];
		sprintf(query, "SELECT key, value FROM strings WHERE key='%s'", DB_STRING_KEYS[key]);
		sqlite3_exec(db, query, strings_cb, str, &err);
	} else {
		sqlite3_exec(db, "SELECT key, value FROM strings", strings_cb, str, &err);
	}
	if (err) {
		LOGE("sqlite3_exec: %s\n", err);
		sqlite3_free(err);
		return 1;
	}
	return 0;
}

static int policy_cb(void *v, int col_num, char **data, char **col_name) {
	auto su = (su_access *) v;
	for (int i = 0; i < col_num; i++) {
		if (strcmp(col_name[i], "policy") == 0)
			su->policy = (policy_t) atoi(data[i]);
		else if (strcmp(col_name[i], "logging") == 0)
			su->log = atoi(data[i]);
		else if (strcmp(col_name[i], "notification") == 0)
			su->notify = atoi(data[i]);
	}
	LOGD("magiskdb: query policy=[%d] log=[%d] notify=[%d]\n", su->policy, su->log, su->notify);
	return 0;
}

int get_uid_policy(sqlite3 *db, int uid, struct su_access *su) {
	if (db == nullptr)
		return 1;
	char query[256], *err;
	sprintf(query, "SELECT policy, logging, notification FROM policies "
			"WHERE uid=%d AND (until=0 OR until>%li)", uid, time(nullptr));
	sqlite3_exec(db, query, policy_cb, su, &err);
	if (err) {
		LOGE("sqlite3_exec: %s\n", err);
		sqlite3_free(err);
		return 1;
	}
	return 0;
}

int validate_manager(char *alt_pkg, int userid, struct stat *st) {
	if (st == nullptr) {
		struct stat stat;
		st = &stat;
	}
	// Prefer DE storage
	const char *base = access("/data/user_de", F_OK) == 0 ? "/data/user_de" : "/data/user";
	char app_path[128];
	sprintf(app_path, "%s/%d/%s", base, userid, alt_pkg[0] ? alt_pkg : "xxx");
	if (stat(app_path, st)) {
		// Check the official package name
		sprintf(app_path, "%s/%d/" JAVA_PACKAGE_NAME, base, userid);
		if (stat(app_path, st)) {
			LOGE("su: cannot find manager");
			memset(st, 0, sizeof(*st));
			alt_pkg[0] = '\0';
			return 1;
		} else {
			// Switch to official package if exists
			strcpy(alt_pkg, JAVA_PACKAGE_NAME);
		}
	}
	return 0;
}

static int print_cb(void *v, int col_num, char **data, char **col_name) {
	for (int i = 0; i < col_num; ++i) {
		if (i) printf("|");
		printf("%s=%s", col_name[i], data[i]);
	}
	printf("\n");
	return 0;
}

int exec_sql(const char *sql) {
	sqlite3 *db = get_magiskdb();
	if (db) {
		char *err;
		sqlite3_exec(db, sql, print_cb, nullptr, &err);
		sqlite3_close_v2(db);
		if (err) {
			fprintf(stderr, "sql_err: %s\n", err);
			sqlite3_free(err);
			return 1;
		}
		return 0;
	}
	return 1;
}
