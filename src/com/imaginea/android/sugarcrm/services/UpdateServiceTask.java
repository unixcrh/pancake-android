package com.imaginea.android.sugarcrm.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.imaginea.android.sugarcrm.ModuleFields;
import com.imaginea.android.sugarcrm.R;
import com.imaginea.android.sugarcrm.SugarCrmApp;
import com.imaginea.android.sugarcrm.SugarCrmSettings;
import com.imaginea.android.sugarcrm.provider.DatabaseHelper;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.Accounts;
import com.imaginea.android.sugarcrm.provider.SugarCRMContent.AccountsColumns;
import com.imaginea.android.sugarcrm.rest.Rest;
import com.imaginea.android.sugarcrm.rest.RestConstants;
import com.imaginea.android.sugarcrm.sync.SyncRecord;
import com.imaginea.android.sugarcrm.util.AsyncServiceTask;
import com.imaginea.android.sugarcrm.util.RelationshipStatus;
import com.imaginea.android.sugarcrm.util.SugarCrmException;
import com.imaginea.android.sugarcrm.util.Util;

/**
 * UpdateServiceTask
 * 
 * @author chander
 * @author vasavi
 */
public class UpdateServiceTask extends AsyncServiceTask<Object, Void, Object> {

    private final Context mContext;

    private String mModuleName;

    private String mParentModuleName;

    private final Map<String, String> mUpdateNameValueMap;

    private String mBeanId;

    private final Uri mUri;

    private String mLinkFieldName;

    private final DatabaseHelper mDbHelper;

    /*
     * represents either delete or update, for local database operations, is
     * always an update on the remote server side
     */
    private final int mCommand;

    public static final String TAG = "UpdateServiceTask";

    /**
     * <p>
     * Constructor for UpdateServiceTask.
     * </p>
     * 
     * @param context
     *            a {@link android.content.Context} object.
     * @param intent
     *            a {@link android.content.Intent} object.
     */
    @SuppressWarnings("unchecked")
    public UpdateServiceTask(Context context, Intent intent) {
        super(context);
        mContext = context;
        mDbHelper = new DatabaseHelper(context);
        final Bundle extras = intent.getExtras();
        mUri = intent.getData();
        // current module being updated/inserted/deleted
        mModuleName = extras.getString(RestConstants.MODULE_NAME);
        mBeanId = extras.getString(RestConstants.BEAN_ID);
        mUpdateNameValueMap = (Map<String, String>) extras
                .getSerializable(RestConstants.NAME_VALUE_LIST);
        mCommand = extras.getInt(Util.COMMAND);
        debug();
    }

    /** {@inheritDoc} */
    @Override
    protected Object doInBackground(Object... params) {
        int updatedRows = 0;
        boolean serverUpdated = false;
        // get network status
        final boolean netOn = Util.isNetworkOn(mContext);
        try {

            String sessionId = ((SugarCrmApp) SugarCrmApp.app).getSessionId();

            final String url = SugarCrmSettings.getSugarRestUrl(mContext);

            final ContentValues values = new ContentValues();
            String updatedBeanId = null;
            // Check network is on
            if (netOn) {
                if ((sessionId == null)
                        || (Rest.seamlessLogin(url, sessionId) == 0)) {
                    final String userName = SugarCrmSettings
                            .getUsername(mContext);
                    final Account account = ((SugarCrmApp) SugarCrmApp.app)
                            .getAccount(userName);
                    sessionId = Rest.loginToSugarCRM(url, userName,
                            AccountManager.get(mContext).getPassword(account));
                    ((SugarCrmApp) SugarCrmApp.app).setSessionId(sessionId);
                }

                switch (mCommand) {
                case Util.INSERT:
                    // inserts with a relationship
                    if (mUri.getPathSegments().size() >= 3) {
                        // get the user id from the username and then add it to
                        // the map
                        final String userBeanName = mUpdateNameValueMap
                                .get(ModuleFields.ASSIGNED_USER_NAME);
                        if (!TextUtils.isEmpty(userBeanName)) {
                            final String userBeanId = mDbHelper
                                    .lookupUserBeanId(userBeanName);
                            mUpdateNameValueMap.put(
                                    ModuleFields.ASSIGNED_USER_ID, userBeanId);
                        }
                        updatedBeanId = Rest.setEntry(url, sessionId,
                                mModuleName, mUpdateNameValueMap);
                        mUpdateNameValueMap
                                .remove(ModuleFields.ASSIGNED_USER_ID);

                        if (updatedBeanId != null) {
                            // get the module name and beanId of the parent from
                            // the URI
                            mParentModuleName = mUri.getPathSegments().get(0);
                            final String rowId = mUri.getPathSegments().get(1);
                            mBeanId = mDbHelper.lookupBeanId(mParentModuleName,
                                    rowId);
                            mLinkFieldName = mDbHelper
                                    .getLinkfieldName(mModuleName);
                            // set the relationship
                            final RelationshipStatus status = Rest
                                    .setRelationship(
                                            url,
                                            sessionId,
                                            mParentModuleName,
                                            mBeanId,
                                            mLinkFieldName,
                                            new String[] { updatedBeanId },
                                            new LinkedHashMap<String, String>(),
                                            Util.EXCLUDE_DELETED_ITEMS);
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.i(TAG,
                                        "created: " + status.getCreatedCount()
                                                + " failed: "
                                                + status.getFailedCount()
                                                + "deleted: "
                                                + status.getDeletedCount());
                            }

                            if (status.getCreatedCount() >= 1) {
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.i(TAG, "Relationship is also set!");
                                }
                                // serverUpdated = true;

                                if (!Util.ACCOUNTS.equals(mModuleName)
                                        && !Util.ACCOUNTS
                                                .equals(mParentModuleName)) {
                                    final String accountName = mUpdateNameValueMap
                                            .get(ModuleFields.ACCOUNT_NAME);
                                    if (!TextUtils.isEmpty(accountName)) {
                                        final SQLiteDatabase db = mDbHelper
                                                .getReadableDatabase();

                                        // get the account bean id for the
                                        // account name
                                        final String selection = AccountsColumns.NAME
                                                + "='" + accountName + "'";
                                        final Cursor cursor = db
                                                .query(DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                                        Accounts.LIST_PROJECTION,
                                                        selection, null, null,
                                                        null, null);
                                        cursor.moveToFirst();
                                        final String newAccountBeanId = cursor
                                                .getString(1);
                                        cursor.close();

                                        mLinkFieldName = mDbHelper
                                                .getLinkfieldName(mModuleName);

                                        // set the relationship with the account
                                        final RelationshipStatus accountStatus = Rest
                                                .setRelationship(
                                                        url,
                                                        sessionId,
                                                        Util.ACCOUNTS,
                                                        newAccountBeanId,
                                                        mLinkFieldName,
                                                        new String[] { updatedBeanId },
                                                        new LinkedHashMap<String, String>(),
                                                        Util.EXCLUDE_DELETED_ITEMS);
                                        if (status.getCreatedCount() >= 1) {
                                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                                Log.d(TAG,
                                                        "Relationship is also set!"
                                                                + "created: "
                                                                + accountStatus
                                                                        .getCreatedCount()
                                                                + " failed: "
                                                                + accountStatus
                                                                        .getFailedCount()
                                                                + " deleted: "
                                                                + accountStatus
                                                                        .getDeletedCount());
                                            }

                                            serverUpdated = true;
                                        } else {
                                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                                Log.d(TAG,
                                                        "setRelationship failed!");
                                            }

                                            serverUpdated = false;
                                        }
                                    }
                                }

                            } else {
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.i(TAG, "setRelationship failed!");
                                }
                                serverUpdated = false;
                            }

                        } else {
                            serverUpdated = false;
                        }
                    } else {
                        // insert case for an orphan module add without any
                        // relationship, the
                        // updatedBeanId is actually a new beanId returned by
                        // server

                        // get the user id from the username and then add it to
                        // the map
                        final String userBeanName = mUpdateNameValueMap
                                .get(ModuleFields.ASSIGNED_USER_NAME);
                        if (!TextUtils.isEmpty(userBeanName)) {
                            final String userBeanId = mDbHelper
                                    .lookupUserBeanId(userBeanName);
                            mUpdateNameValueMap.put(
                                    ModuleFields.ASSIGNED_USER_ID, userBeanId);
                        }
                        updatedBeanId = Rest.setEntry(url, sessionId,
                                mModuleName, mUpdateNameValueMap);
                        mUpdateNameValueMap
                                .remove(ModuleFields.ASSIGNED_USER_ID);

                        if (updatedBeanId != null) {
                            if (!Util.ACCOUNTS.equals(mModuleName)) {
                                final String accountName = mUpdateNameValueMap
                                        .get(ModuleFields.ACCOUNT_NAME);
                                if (!TextUtils.isEmpty(accountName)) {

                                    final SQLiteDatabase db = mDbHelper
                                            .getReadableDatabase();

                                    // get the account bean id for the account
                                    // name
                                    final String selection = AccountsColumns.NAME
                                            + "='" + accountName + "'";
                                    final Cursor cursor = db.query(
                                            DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                            Accounts.LIST_PROJECTION,
                                            selection, null, null, null, null);
                                    cursor.moveToFirst();
                                    final String newAccountBeanId = cursor
                                            .getString(1);
                                    cursor.close();

                                    mLinkFieldName = mDbHelper
                                            .getLinkfieldName(mModuleName);

                                    // set the relationship with the account
                                    final RelationshipStatus status = Rest
                                            .setRelationship(
                                                    url,
                                                    sessionId,
                                                    Util.ACCOUNTS,
                                                    newAccountBeanId,
                                                    mLinkFieldName,
                                                    new String[] { updatedBeanId },
                                                    new LinkedHashMap<String, String>(),
                                                    Util.EXCLUDE_DELETED_ITEMS);
                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(TAG,
                                                "created: "
                                                        + status.getCreatedCount()
                                                        + " failed: "
                                                        + status.getFailedCount()
                                                        + " deleted: "
                                                        + status.getDeletedCount());
                                    }

                                    if (status.getCreatedCount() >= 1) {
                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "Relationship is also set!");
                                        }

                                        serverUpdated = true;
                                    } else {
                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "setRelationship failed!");
                                        }

                                        serverUpdated = false;
                                    }

                                } else {
                                    // if the updatedBeanId is not null, module
                                    // is not Accounts and
                                    // accountName is null
                                    serverUpdated = true;
                                }
                            } else {
                                // if the updatedBeanId is not null and the
                                // module is Accounts
                                serverUpdated = true;
                            }
                        } else {
                            // if the updatedBeanId is null
                            serverUpdated = false;
                        }

                    }
                    break;

                case Util.UPDATE:
                    String serverUpdatedBeanId = null;
                    // update the bean via relationship
                    if (mUri.getPathSegments().size() >= 3) {

                        // get the module name and beanId of the parent using
                        // the rowId in the URI
                        mParentModuleName = mUri.getPathSegments().get(0);
                        String rowId = mUri.getPathSegments().get(1);
                        mBeanId = mDbHelper.lookupBeanId(mParentModuleName,
                                rowId);

                        // get the related module name and beanId from the URI
                        final String moduleName = mUri.getPathSegments().get(2);
                        rowId = mUri.getPathSegments().get(3);
                        updatedBeanId = mDbHelper.lookupBeanId(moduleName,
                                rowId);
                        mLinkFieldName = mDbHelper.getLinkfieldName(moduleName);

                        // get the user id from the username and then add it to
                        // the map
                        final String userBeanName = mUpdateNameValueMap
                                .get(ModuleFields.ASSIGNED_USER_NAME);
                        if (!TextUtils.isEmpty(userBeanName)) {
                            final String userBeanId = mDbHelper
                                    .lookupUserBeanId(userBeanName);
                            mUpdateNameValueMap.put(
                                    ModuleFields.ASSIGNED_USER_ID, userBeanId);
                        }

                        // update the bean
                        serverUpdatedBeanId = Rest.setEntry(url, sessionId,
                                moduleName, mUpdateNameValueMap);
                        mUpdateNameValueMap
                                .remove(ModuleFields.ASSIGNED_USER_ID);

                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "updatedBeanId : " + updatedBeanId
                                    + "  serverUpdatedBeanId : "
                                    + serverUpdatedBeanId);
                        }

                        // if the actual beanId (updatedBeanId) is equal to
                        // serverUpdatedBeanId
                        // (beanId returned by server)
                        if (serverUpdatedBeanId.equals(updatedBeanId)) {

                            // get the accountName from the name-value map
                            final String accountName = mUpdateNameValueMap
                                    .get(ModuleFields.ACCOUNT_NAME);
                            // if the accountName is not null
                            if (!TextUtils.isEmpty(accountName)) {

                                final SQLiteDatabase db = mDbHelper
                                        .getReadableDatabase();

                                // get the account bean id for the account name
                                String selection = AccountsColumns.NAME + "='"
                                        + accountName + "'";
                                Cursor cursor = db.query(
                                        DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                        Accounts.LIST_PROJECTION, selection,
                                        null, null, null, null);
                                cursor.moveToFirst();
                                final String newAccountBeanId = cursor
                                        .getString(1);
                                cursor.close();

                                // get the related account id for the bean
                                selection = mDbHelper
                                        .getAccountRelationsSelection(moduleName)
                                        + "="
                                        + rowId
                                        + " AND "
                                        + ModuleFields.DELETED
                                        + "="
                                        + Util.EXCLUDE_DELETED_ITEMS;
                                cursor = db
                                        .query(mDbHelper
                                                .getAccountRelationsTableName(moduleName),
                                                new String[] { ModuleFields.ACCOUNT_ID },
                                                selection, null, null, null,
                                                null);
                                String accountRowId = null;
                                if (cursor.moveToFirst()) {
                                    accountRowId = cursor.getString(0);
                                }
                                cursor.close();

                                String accountBeanId = null;
                                // if the related account id, i.e. accountRowId
                                // is not null, =>
                                // there already exists a relationship.
                                if (!TextUtils.isEmpty(accountRowId)) {

                                    // get the account bean id (accountBeanId)
                                    // for the accountRowId
                                    selection = AccountsColumns.ID + "="
                                            + accountRowId;
                                    cursor = db
                                            .query(DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                                    new String[] { AccountsColumns.BEAN_ID },
                                                    selection, null, null,
                                                    null, null);
                                    cursor.moveToFirst();
                                    accountBeanId = cursor.getString(0);
                                    cursor.close();

                                    // if the accountBeanId (from the old
                                    // relation) is equal to the
                                    // newAccountBeanId => the relationship is
                                    // the same. Otherwise,
                                    // set the old relationship with the delete
                                    // flag set to '1'.
                                    if (!accountBeanId.equals(newAccountBeanId)) {
                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "updating delete flag for relationship with account bean id : "
                                                            + accountBeanId
                                                            + " linkFieldName : "
                                                            + mDbHelper
                                                                    .getLinkfieldName(mModuleName)
                                                            + " bean Id : "
                                                            + updatedBeanId);
                                        }

                                        final RelationshipStatus status = Rest
                                                .setRelationship(
                                                        url,
                                                        sessionId,
                                                        Util.ACCOUNTS,
                                                        accountBeanId,
                                                        mLinkFieldName,
                                                        new String[] { updatedBeanId },
                                                        new LinkedHashMap<String, String>(),
                                                        Util.DELETED_ITEM);

                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "updating delete flag for relationship is also set!"
                                                            + "created: "
                                                            + status.getCreatedCount()
                                                            + " failed: "
                                                            + status.getFailedCount()
                                                            + " deleted: "
                                                            + status.getDeletedCount());
                                        }
                                    }
                                }

                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG,
                                            "updating relationship with parent module : "
                                                    + mParentModuleName
                                                    + " parent bean Id : "
                                                    + mBeanId);
                                    Log.d(TAG,
                                            "updating relationship with link field name : "
                                                    + mLinkFieldName
                                                    + " updated bean Id : "
                                                    + updatedBeanId);
                                }
                                // set the new relationship
                                final RelationshipStatus status = Rest
                                        .setRelationship(
                                                url,
                                                sessionId,
                                                mParentModuleName,
                                                newAccountBeanId,
                                                mLinkFieldName,
                                                new String[] { updatedBeanId },
                                                new LinkedHashMap<String, String>(),
                                                Util.EXCLUDE_DELETED_ITEMS);
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG,
                                            "created: "
                                                    + status.getCreatedCount()
                                                    + " failed: "
                                                    + status.getFailedCount()
                                                    + " deleted: "
                                                    + status.getDeletedCount());
                                }

                                if (status.getCreatedCount() >= 1) {
                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(TAG, "Relationship is also set!");
                                    }
                                    serverUpdated = true;
                                } else {
                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(TAG, "setRelationship failed!");
                                    }
                                    serverUpdated = false;
                                }

                                db.close();
                            } else {
                                // if the updatedBeanId is not null, module is
                                // not Accounts and
                                // accountName is null
                                serverUpdated = true;
                            }
                        } else {
                            // a new bean was created instead of sending back
                            // the same updated bean
                            serverUpdated = false;
                        }
                    } else {
                        // update an orphan

                        // get the module name and beanId from the URI
                        mModuleName = mUri.getPathSegments().get(0);
                        final String rowId = mUri.getPathSegments().get(1);
                        mBeanId = mDbHelper.lookupBeanId(mModuleName, rowId);
                        mUpdateNameValueMap.put(SugarCRMContent.SUGAR_BEAN_ID,
                                mBeanId);

                        // get the user id from the username and then add it to
                        // the map
                        final String userBeanName = mUpdateNameValueMap
                                .get(ModuleFields.ASSIGNED_USER_NAME);
                        if (!TextUtils.isEmpty(userBeanName)) {
                            final String userBeanId = mDbHelper
                                    .lookupUserBeanId(userBeanName);
                            mUpdateNameValueMap.put(
                                    ModuleFields.ASSIGNED_USER_ID, userBeanId);
                        }

                        // update the bean
                        updatedBeanId = Rest.setEntry(url, sessionId,
                                mModuleName, mUpdateNameValueMap);
                        mUpdateNameValueMap
                                .remove(ModuleFields.ASSIGNED_USER_ID);
                        mUpdateNameValueMap
                                .remove(SugarCRMContent.SUGAR_BEAN_ID);

                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "updatedBeanId : " + updatedBeanId
                                    + "  mBeanId : " + mBeanId);
                        }

                        // if the actual beanId (mBeanId) is equal to
                        // updatedBeanId (beanId returned
                        // by server)
                        if (mBeanId.equals(updatedBeanId)) {

                            if (!Util.ACCOUNTS.equals(mModuleName)) {

                                // get the accountName from the name-value map
                                final String accountName = mUpdateNameValueMap
                                        .get(ModuleFields.ACCOUNT_NAME);
                                // if accountName is not null
                                if (!TextUtils.isEmpty(accountName)) {

                                    final SQLiteDatabase db = mDbHelper
                                            .getReadableDatabase();

                                    // get the account bean id for the account
                                    // name
                                    String selection = AccountsColumns.NAME
                                            + "='" + accountName + "'";
                                    Cursor cursor = db.query(
                                            DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                            Accounts.LIST_PROJECTION,
                                            selection, null, null, null, null);
                                    cursor.moveToFirst();
                                    final String newAccountBeanId = cursor
                                            .getString(1);
                                    cursor.close();

                                    // get the related account id for the bean
                                    selection = mDbHelper
                                            .getAccountRelationsSelection(mModuleName)
                                            + "="
                                            + rowId
                                            + " AND "
                                            + ModuleFields.DELETED
                                            + "="
                                            + Util.EXCLUDE_DELETED_ITEMS;
                                    cursor = db
                                            .query(mDbHelper
                                                    .getAccountRelationsTableName(mModuleName),
                                                    new String[] { ModuleFields.ACCOUNT_ID },
                                                    selection, null, null,
                                                    null, null);
                                    String accountRowId = null;
                                    if (cursor.moveToFirst()) {
                                        accountRowId = cursor.getString(0);
                                    }
                                    cursor.close();

                                    String accountBeanId = null;
                                    // if the related account id, i.e.
                                    // accountRowId is not null, =>
                                    // there already exists a relationship.
                                    if (!TextUtils.isEmpty(accountRowId)) {

                                        // get the account bean id
                                        // (accountBeanId) for the
                                        // accountRowId
                                        selection = AccountsColumns.ID + "="
                                                + accountRowId;
                                        cursor = db
                                                .query(DatabaseHelper.ACCOUNTS_TABLE_NAME,
                                                        new String[] { AccountsColumns.BEAN_ID },
                                                        selection, null, null,
                                                        null, null);
                                        cursor.moveToFirst();
                                        accountBeanId = cursor.getString(0);
                                        cursor.close();

                                        // if the accountBeanId (from the old
                                        // relation) is equal to
                                        // the newAccountBeanId => the
                                        // relationship is the same.
                                        // Otherwise, set the old relationship
                                        // with the delete flag
                                        // set to '1'.
                                        if (!accountBeanId
                                                .equals(newAccountBeanId)) {
                                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                                Log.d(TAG,
                                                        "updating delete flag for relationship with account bean id : "
                                                                + accountBeanId
                                                                + " linkFieldName : "
                                                                + mDbHelper
                                                                        .getLinkfieldName(mModuleName)
                                                                + " bean Id : "
                                                                + updatedBeanId);
                                            }
                                            final RelationshipStatus status = Rest
                                                    .setRelationship(
                                                            url,
                                                            sessionId,
                                                            Util.ACCOUNTS,
                                                            accountBeanId,
                                                            mDbHelper
                                                                    .getLinkfieldName(mModuleName),
                                                            new String[] { updatedBeanId },
                                                            new LinkedHashMap<String, String>(),
                                                            Util.DELETED_ITEM);

                                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                                Log.d(TAG,
                                                        "updating delete flag for relationship is also set!"
                                                                + "created: "
                                                                + status.getCreatedCount()
                                                                + " failed: "
                                                                + status.getFailedCount()
                                                                + " deleted: "
                                                                + status.getDeletedCount());
                                            }
                                        }
                                    }

                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(TAG,
                                                "updating relationship with parent module : "
                                                        + Util.ACCOUNTS
                                                        + " parent bean Id : "
                                                        + newAccountBeanId);
                                        Log.d(TAG,
                                                "updating relationship with link field name : "
                                                        + mDbHelper
                                                                .getLinkfieldName(mModuleName)
                                                        + " updated bean Id : "
                                                        + updatedBeanId);
                                    }

                                    // set the new relationship
                                    final RelationshipStatus status = Rest
                                            .setRelationship(
                                                    url,
                                                    sessionId,
                                                    Util.ACCOUNTS,
                                                    newAccountBeanId,
                                                    mDbHelper
                                                            .getLinkfieldName(mModuleName),
                                                    new String[] { updatedBeanId },
                                                    new LinkedHashMap<String, String>(),
                                                    Util.EXCLUDE_DELETED_ITEMS);
                                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                                        Log.d(TAG,
                                                "created: "
                                                        + status.getCreatedCount()
                                                        + " failed: "
                                                        + status.getFailedCount()
                                                        + " deleted: "
                                                        + status.getDeletedCount());
                                    }

                                    if (status.getCreatedCount() >= 1) {
                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "Relationship is also set!");
                                        }

                                        serverUpdated = true;
                                    } else {
                                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                                            Log.d(TAG,
                                                    "setRelationship failed!");
                                        }

                                        serverUpdated = false;
                                    }

                                    db.close();
                                } else {
                                    // if the updatedBeanId is not null, module
                                    // is not Accounts and
                                    // accountName is null
                                    serverUpdated = true;
                                }
                            } else {
                                // if the updatedBeanId is same as mBeanId and
                                // the module is
                                // Accounts
                                serverUpdated = true;
                            }
                        } else {
                            // a new bean was created instead of sending back
                            // the same updated bean
                            serverUpdated = false;
                        }
                    }
                    break;
                case Util.DELETE:
                    // get the module name and bean id from the URI
                    mModuleName = mUri.getPathSegments().get(0);
                    final String rowId = mUri.getPathSegments().get(1);
                    mBeanId = mDbHelper.lookupBeanId(mModuleName, rowId);
                    mUpdateNameValueMap.put(SugarCRMContent.SUGAR_BEAN_ID,
                            mBeanId);
                    // delete: set entry with the delete flag as '1'
                    updatedBeanId = Rest.setEntry(url, sessionId, mModuleName,
                            mUpdateNameValueMap);
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "updatedBeanId : " + updatedBeanId
                                + "  mBeanId : " + mBeanId);
                    }

                    mUpdateNameValueMap.remove(SugarCRMContent.SUGAR_BEAN_ID);

                    if (mBeanId.equals(updatedBeanId)) {
                        serverUpdated = true;
                    } else {
                        serverUpdated = false;
                    }
                    break;
                }
            }

            switch (mCommand) {
            case Util.INSERT:

                for (final String key : mUpdateNameValueMap.keySet()) {
                    values.put(key, mUpdateNameValueMap.get(key));
                }

                // if the update is successful on the server-side
                if (serverUpdated) {
                    // add updatedBeanId to the values map
                    values.put(SugarCRMContent.SUGAR_BEAN_ID, updatedBeanId);
                    final Uri insertResultUri = mContext.getContentResolver()
                            .insert(mUri, values);
                    Log.i(TAG, "insertResultURi - " + insertResultUri);
                    updatedRows = 1;
                } else {
                    /*
                     * we do not have a beanId to add to our valueMap. we add a
                     * randomly generated beanId -with prefix "Sync" only for
                     * debugging purposes, do not use to distinguish with sync
                     * and normal operations
                     */
                    values.put(SugarCRMContent.SUGAR_BEAN_ID,
                            "Sync" + UUID.randomUUID());
                    final Uri insertResultUri = mContext.getContentResolver()
                            .insert(mUri, values);
                    // after success url insertion, we set the updatedRow to 1
                    // so we don't get a
                    // fail msg
                    updatedRows = 1;
                    Log.i(TAG, "insertResultURi - " + insertResultUri);
                    insertSyncRecord(insertResultUri);
                }
                break;

            case Util.UPDATE:

                for (final String key : mUpdateNameValueMap.keySet()) {
                    values.put(key, mUpdateNameValueMap.get(key));
                }

                if (serverUpdated) {
                    updatedRows = mContext.getContentResolver().update(mUri,
                            values, null, null);
                } else {
                    // TODO: check this
                    updatedRows = mContext.getContentResolver().update(mUri,
                            values, null, null);
                    if (updatedRows > 0) {
                        updateSyncRecord();
                    }
                }
                break;

            case Util.DELETE:

                if (serverUpdated) {
                    updatedRows = mContext.getContentResolver().delete(mUri,
                            null, null);
                } else {
                    // this will update just the delete column, sets it to 1
                    values.put(ModuleFields.DELETED, Util.DELETED_ITEM);
                    updatedRows = mContext.getContentResolver().update(mUri,
                            values, null, null);
                    if (updatedRows > 0) {
                        updateSyncRecord();
                    }
                }
                break;

            }

        } catch (final Exception e) {
            Log.e(TAG, e.getMessage(), e);
            sendUpdateStatus(netOn, serverUpdated, updatedRows);
        }
        mDbHelper.close();
        sendUpdateStatus(netOn, serverUpdated, updatedRows);
        return null;
    }

    private void sendUpdateStatus(boolean netOn, boolean serverUpdated,
            int updatedRows) {

        // If the update fails when the network is ON, display the message in
        // the
        // activity
        if (netOn) {

            if (!serverUpdated) {
                SugarService.sendMessage(R.id.status, String.format(
                        mContext.getString(R.string.serverUpdateFailed),
                        getCommandStr()));
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "update to server failed");
                }
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "updated server successful");
                }
                SugarService.sendMessage(R.id.status, String.format(
                        mContext.getString(R.string.serverUpdateSuccess),
                        getCommandStr()));
            }
        } else {
            // pass the success/failure msg to activity

            if (updatedRows > 0) {
                Log.v(TAG, "update successful");
                SugarService.sendMessage(R.id.status, String.format(
                        mContext.getString(R.string.serverUpdateSuccess),
                        getCommandStr()));
            } else {
                SugarService.sendMessage(R.id.status, String.format(
                        mContext.getString(R.string.updateFailed),
                        getCommandStr()));
                Log.v(TAG, "update failed");
            }

        }
    }

    private String getCommandStr() {
        switch (mCommand) {
        case Util.INSERT:
            return mContext.getString(R.string.insert);
        case Util.UPDATE:
            return mContext.getString(R.string.update);
        case Util.DELETE:
            return mContext.getString(R.string.delete);
        default:
            return "";
        }
    }

    private void updateSyncRecord() throws SugarCrmException {
        final long syncId = Long.parseLong(mUri.getPathSegments().get(1));
        final SyncRecord rec = mDbHelper.getSyncRecord(syncId, mModuleName);
        debug(rec);
        if (rec == null) {
            insertSyncRecord(mUri);
        } else {
            if (mUri.getPathSegments().size() == 3) {
                rec.syncRelatedId = Long.parseLong(mUri.getPathSegments()
                        .get(3));
            }
            mDbHelper.updateSyncRecord(rec);
        }
    }

    private void insertSyncRecord(Uri insertUri) throws SugarCrmException {
        final SyncRecord record = new SyncRecord();
        record.syncId = Long.parseLong(insertUri.getPathSegments().get(1));
        if (mUri.getPathSegments().size() == 3) {
            record.syncRelatedId = Long.parseLong(insertUri.getPathSegments()
                    .get(3));
        }
        record.syncCommand = mCommand;
        record.moduleName = mLinkFieldName != null ? mParentModuleName
                : mModuleName;
        record.relatedModuleName = mModuleName;
        record.status = Util.UNSYNCED;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            debug(record);
        }
        mDbHelper.insertSyncRecord(record);
    }

    private void debug(SyncRecord record) {
        if (record == null) {
            Log.d(TAG, "Sync Record is null");
            return;
        }
        Log.d(TAG, " id:" + record._id);
        Log.d(TAG, "Sync id:" + record.syncId);
        Log.d(TAG, "Sync command:" + record.syncCommand);
        Log.d(TAG, "Module name:" + record.moduleName);
        Log.d(TAG, "Related Module Name:" + record.relatedModuleName);
        Log.d(TAG, "Sync command:"
                + (record.syncCommand == 1 ? "INSERT" : "UPDATE/DELETE"));
        Log.d(TAG, "Sync Status:"
                + (record.status == Util.UNSYNCED ? "UNSYNCHD" : "CONFLICTS"));
    }

    private void debug() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "size : " + mUri.getPathSegments().size());
            Log.d(TAG, "mParentModuleName : " + mParentModuleName
                    + " linkFieldName : " + mLinkFieldName);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.i(TAG, "linkFieldName : " + mLinkFieldName);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onCancelled() {
        super.onCancelled();
    }
}
