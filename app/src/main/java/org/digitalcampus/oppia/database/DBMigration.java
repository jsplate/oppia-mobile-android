package org.digitalcampus.oppia.database;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;

import org.digitalcampus.oppia.activity.PrefsActivity;
import org.digitalcampus.oppia.application.App;
import org.digitalcampus.oppia.model.db_model.UserPreference;

import java.util.List;

import javax.inject.Inject;

public class DBMigration {

    public static final int DATA_MIGRATION_LAST_VERSION = 1;

    private final Context context;

    @Inject
    SharedPreferences prefs;

    public static DBMigration newInstance(Context context) {
        return new DBMigration(context);
    }

    private DBMigration(Context context) {
        this.context = context;

        App app = (App) context.getApplicationContext();
        app.getComponent().inject(this);
    }

    /**
     * For new Room database migration. If there is new data prepared to migrate, proper method will be called
     */
    public void checkMigrationStatus() {

        int currentVersion = prefs.getInt(PrefsActivity.PREF_DATA_ROOM_MIGRATON_VERSION, 0);

        if (currentVersion == DATA_MIGRATION_LAST_VERSION) {
            return;
        }

        DbHelper dbHelper = DbHelper.getInstance(context);

        try {
            runMigrationsByVersion(currentVersion, dbHelper);
            prefs.edit().putInt(PrefsActivity.PREF_DATA_ROOM_MIGRATON_VERSION, DATA_MIGRATION_LAST_VERSION).apply();
        } catch (SQLiteException e) {
            e.printStackTrace();
        }

    }

    private void runMigrationsByVersion(int currentVersion, DbHelper dbHelper) throws SQLiteException {

        if (currentVersion < 1) {
            copyUserPreferencesData(dbHelper);
            copyUserCustomFieldsData(dbHelper);
        }

    }


    private static void copyUserPreferencesData(DbHelper dbHelper) throws SQLiteException {
        try {
            List<UserPreference> userPreferences = dbHelper.getAllUserPreferences();
            App.getDb().userPreferenceDao().insertAll(userPreferences);
            dbHelper.dropTable(DbHelper.USER_PREFS_TABLE);
        } catch (SQLiteException e) {
            e.printStackTrace();
        }
    }

    private static void copyUserCustomFieldsData(DbHelper dbHelper) {

    }
}
