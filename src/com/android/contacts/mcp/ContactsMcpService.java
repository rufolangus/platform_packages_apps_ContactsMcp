/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.mcp;

import android.Manifest;
import android.app.Service;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.llm.IMcpToolProvider;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * MCP tool provider for Contacts.
 *
 * <p>Exposes the device's contacts as MCP tools that the LLM System Service
 * can invoke. This is the reference implementation showing how any app
 * can become an MCP server.
 *
 * <h3>Tools provided:</h3>
 * <ul>
 *   <li>{@code search_contacts} — search by name, phone, or email</li>
 *   <li>{@code get_contact} — get full details for a contact by name</li>
 *   <li>{@code list_favorites} — list starred contacts</li>
 * </ul>
 *
 * <h3>How it works:</h3>
 * <ol>
 *   <li>User says "What's John's phone number?" to the launcher</li>
 *   <li>Launcher submits to LLM System Service</li>
 *   <li>LLM sees {@code get_contact} tool, generates tool_call with name="John"</li>
 *   <li>LLM Service binds to this service, calls invokeTool("get_contact", ...)</li>
 *   <li>This service queries ContactsProvider, returns JSON result</li>
 *   <li>LLM incorporates result and responds: "John's number is 555-1234"</li>
 * </ol>
 */
public class ContactsMcpService extends Service {

    private static final String TAG = "ContactsMcp";
    private static final int MAX_RESULTS = 20;

    private final IMcpToolProvider.Stub mBinder = new IMcpToolProvider.Stub() {

        @Override
        public String invokeTool(String toolName, String argumentsJson) {
            Log.i(TAG, "invokeTool: " + toolName + " args=" + argumentsJson);
            try {
                JSONObject args = new JSONObject(
                        argumentsJson != null ? argumentsJson : "{}");

                switch (toolName) {
                    case "search_contacts":
                        return searchContacts(args.optString("query", ""));
                    case "get_contact":
                        return getContact(args.optString("name", ""));
                    case "list_favorites":
                        return listFavorites();
                    case "add_contact":
                        return addContact(
                                args.optString("name", ""),
                                args.optString("phone", ""),
                                args.optString("email", ""));
                    case "update_contact":
                        return updateContact(
                                args.optString("name", ""),
                                args.optString("phone", ""),
                                args.optString("email", ""));
                    default:
                        return error("Unknown tool: " + toolName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in " + toolName, e);
                return error(e.getMessage());
            }
        }

        @Override
        public String readResource(String resourceName) {
            if ("all_contacts".equals(resourceName)) {
                return searchContacts("");
            }
            return error("Unknown resource: " + resourceName);
        }

        @Override
        public String listResources(String uriPattern) {
            try {
                JSONArray arr = new JSONArray();
                JSONObject resource = new JSONObject();
                resource.put("name", "all_contacts");
                resource.put("uri", "content://com.android.contacts/contacts");
                resource.put("mimeType", "application/json");
                resource.put("description", "All contacts on the device");
                arr.put(resource);
                return arr.toString();
            } catch (JSONException e) {
                return "[]";
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // ------------------------------------------------------------------
    // Tool implementations
    // ------------------------------------------------------------------

    /**
     * Search contacts by name, phone number, or email.
     */
    private String searchContacts(String query) {
        ContentResolver cr = getContentResolver();
        JSONArray results = new JSONArray();

        String selection = null;
        String[] selectionArgs = null;
        if (query != null && !query.isEmpty()) {
            selection = Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?";
            selectionArgs = new String[]{"%" + query + "%"};
        }

        try (Cursor cursor = cr.query(
                Contacts.CONTENT_URI,
                new String[]{
                        Contacts._ID,
                        Contacts.DISPLAY_NAME_PRIMARY,
                        Contacts.STARRED,
                        Contacts.HAS_PHONE_NUMBER,
                        Contacts.PHOTO_THUMBNAIL_URI
                },
                selection,
                selectionArgs,
                Contacts.DISPLAY_NAME_PRIMARY + " ASC")) {

            if (cursor == null) {
                return error("Failed to query contacts");
            }

            int count = 0;
            while (cursor.moveToNext() && count < MAX_RESULTS) {
                try {
                    JSONObject contact = new JSONObject();
                    long contactId = cursor.getLong(0);
                    contact.put("id", contactId);
                    contact.put("name", cursor.getString(1));
                    contact.put("starred", cursor.getInt(2) == 1);

                    // Get first phone number if available
                    if (cursor.getInt(3) > 0) {
                        String phone = getFirstPhone(cr, contactId);
                        if (phone != null) {
                            contact.put("phone", phone);
                        }
                    }

                    // Get first email
                    String email = getFirstEmail(cr, contactId);
                    if (email != null) {
                        contact.put("email", email);
                    }

                    results.put(contact);
                    count++;
                } catch (JSONException e) {
                    // skip malformed entry
                }
            }
        }

        try {
            JSONObject result = new JSONObject();
            result.put("contacts", results);
            result.put("count", results.length());
            if (query != null && !query.isEmpty()) {
                result.put("query", query);
            }
            return result.toString();
        } catch (JSONException e) {
            return error("JSON error");
        }
    }

    /**
     * Get full details for a contact by name.
     */
    private String getContact(String name) {
        if (name == null || name.isEmpty()) {
            return error("Name is required");
        }

        ContentResolver cr = getContentResolver();

        // Find the contact by name
        try (Cursor cursor = cr.query(
                Contacts.CONTENT_URI,
                new String[]{
                        Contacts._ID,
                        Contacts.DISPLAY_NAME_PRIMARY,
                        Contacts.STARRED
                },
                Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?",
                new String[]{"%" + name + "%"},
                null)) {

            if (cursor == null || !cursor.moveToFirst()) {
                return error("No contact found matching: " + name);
            }

            long contactId = cursor.getLong(0);
            JSONObject contact = new JSONObject();
            contact.put("id", contactId);
            contact.put("name", cursor.getString(1));
            contact.put("starred", cursor.getInt(2) == 1);

            // Get all phone numbers
            JSONArray phones = new JSONArray();
            try (Cursor phoneCursor = cr.query(
                    Phone.CONTENT_URI,
                    new String[]{Phone.NUMBER, Phone.TYPE, Phone.LABEL},
                    Phone.CONTACT_ID + " = ?",
                    new String[]{String.valueOf(contactId)},
                    null)) {
                if (phoneCursor != null) {
                    while (phoneCursor.moveToNext()) {
                        JSONObject phone = new JSONObject();
                        phone.put("number", phoneCursor.getString(0));
                        phone.put("type", Phone.getTypeLabel(
                                getResources(),
                                phoneCursor.getInt(1),
                                phoneCursor.getString(2)).toString());
                        phones.put(phone);
                    }
                }
            }
            contact.put("phones", phones);

            // Get all emails
            JSONArray emails = new JSONArray();
            try (Cursor emailCursor = cr.query(
                    Email.CONTENT_URI,
                    new String[]{Email.ADDRESS, Email.TYPE, Email.LABEL},
                    Email.CONTACT_ID + " = ?",
                    new String[]{String.valueOf(contactId)},
                    null)) {
                if (emailCursor != null) {
                    while (emailCursor.moveToNext()) {
                        JSONObject emailObj = new JSONObject();
                        emailObj.put("address", emailCursor.getString(0));
                        emailObj.put("type", Email.getTypeLabel(
                                getResources(),
                                emailCursor.getInt(1),
                                emailCursor.getString(2)).toString());
                        emails.put(emailObj);
                    }
                }
            }
            contact.put("emails", emails);

            return contact.toString();
        } catch (Exception e) {
            return error("Error looking up contact: " + e.getMessage());
        }
    }

    /**
     * List starred/favorite contacts.
     */
    private String listFavorites() {
        ContentResolver cr = getContentResolver();
        JSONArray results = new JSONArray();

        try (Cursor cursor = cr.query(
                Contacts.CONTENT_URI,
                new String[]{
                        Contacts._ID,
                        Contacts.DISPLAY_NAME_PRIMARY,
                        Contacts.HAS_PHONE_NUMBER
                },
                Contacts.STARRED + " = 1",
                null,
                Contacts.DISPLAY_NAME_PRIMARY + " ASC")) {

            if (cursor == null) {
                return error("Failed to query favorites");
            }

            while (cursor.moveToNext()) {
                try {
                    JSONObject contact = new JSONObject();
                    long contactId = cursor.getLong(0);
                    contact.put("name", cursor.getString(1));

                    if (cursor.getInt(2) > 0) {
                        String phone = getFirstPhone(cr, contactId);
                        if (phone != null) {
                            contact.put("phone", phone);
                        }
                    }

                    results.put(contact);
                } catch (JSONException e) {
                    // skip
                }
            }
        }

        try {
            JSONObject result = new JSONObject();
            result.put("favorites", results);
            result.put("count", results.length());
            return result.toString();
        } catch (JSONException e) {
            return error("JSON error");
        }
    }

    // ------------------------------------------------------------------
    // Write tools
    // ------------------------------------------------------------------

    /**
     * Create a new contact. Uses {@code ACCOUNT_TYPE=null} / {@code
     * ACCOUNT_NAME=null} so the contact lives in the "local device"
     * account — works on stock Cuttlefish which has no Google account
     * signed in.
     */
    private String addContact(String name, String phone, String email) {
        if (name == null || name.trim().isEmpty()) {
            return error("name is required");
        }
        if (!hasWriteContacts()) {
            String err = requestWriteContactsOrError();
            if (err != null) return err;
        }
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build());
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name.trim())
                .build());
        if (phone != null && !phone.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build());
        }
        if (email != null && !email.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, email)
                    .withValue(Email.TYPE, Email.TYPE_HOME)
                    .build());
        }
        try {
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            JSONObject ok = new JSONObject();
            ok.put("status", "added");
            ok.put("name", name.trim());
            if (phone != null && !phone.isEmpty()) ok.put("phone", phone);
            if (email != null && !email.isEmpty()) ok.put("email", email);
            return ok.toString();
        } catch (SecurityException se) {
            return needsPermissionError();
        } catch (Exception e) {
            Log.e(TAG, "addContact failed", e);
            return error("add failed: " + e.getMessage());
        }
    }

    /**
     * Append a phone or email to an existing contact matched by name.
     * Does not edit existing values — safer default; the model can call
     * again with different values if the user wants a replacement.
     */
    private String updateContact(String name, String phone, String email) {
        if (name == null || name.trim().isEmpty()) {
            return error("name is required");
        }
        if ((phone == null || phone.isEmpty())
                && (email == null || email.isEmpty())) {
            return error("provide phone or email");
        }
        if (!hasWriteContacts()) {
            String err = requestWriteContactsOrError();
            if (err != null) return err;
        }
        ContentResolver cr = getContentResolver();
        long rawContactId = -1;
        String resolvedName = null;
        // Find the raw contact id for the first matching display name.
        try (Cursor c = cr.query(RawContacts.CONTENT_URI,
                new String[]{RawContacts._ID, RawContacts.CONTACT_ID,
                        RawContacts.DISPLAY_NAME_PRIMARY},
                RawContacts.DISPLAY_NAME_PRIMARY + " LIKE ? AND "
                        + RawContacts.DELETED + "=0",
                new String[]{"%" + name + "%"},
                null)) {
            if (c != null && c.moveToFirst()) {
                rawContactId = c.getLong(0);
                resolvedName = c.getString(2);
            }
        }
        if (rawContactId < 0) {
            return error("no contact matches: " + name);
        }
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        if (phone != null && !phone.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build());
        }
        if (email != null && !email.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, email)
                    .withValue(Email.TYPE, Email.TYPE_HOME)
                    .build());
        }
        try {
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            JSONObject ok = new JSONObject();
            ok.put("status", "updated");
            ok.put("name", resolvedName);
            if (phone != null && !phone.isEmpty()) ok.put("phone_added", phone);
            if (email != null && !email.isEmpty()) ok.put("email_added", email);
            return ok.toString();
        } catch (SecurityException se) {
            return needsPermissionError();
        } catch (Exception e) {
            Log.e(TAG, "updateContact failed", e);
            return error("update failed: " + e.getMessage());
        }
    }

    private boolean hasWriteContacts() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * If the runtime WRITE_CONTACTS permission is missing, return a
     * structured error immediately (~5 ms). The launcher's
     * {@code PermissionRequiredCard} renders an "Open settings" CTA
     * that deep-links to ACTION_APPLICATION_DETAILS_SETTINGS — the
     * correct UX for a missing runtime permission.
     *
     * <p>v0.5.1: the previous implementation launched
     * {@link PermissionRequestActivity} via {@code startActivity} from
     * this bound-service context. On Android 15 that start silently
     * fails under BAL (background-activity-launch) gating; the service
     * then sits on a 30 s latch waiting for a dialog that never
     * renders, while the framework dispatcher's own latch (10 s in
     * v0.5, now 60 s in v0.5.1) would time out first and return
     * {@code "tool timeout"} upstream — so the launcher never saw the
     * {@code needs_permission} JSON and {@code PermissionRequiredCard}
     * never fired. Returning the error immediately is the honest flow
     * on Android 15.
     *
     * <p>The proper fix (v0.6) is a PendingIntent proxied through the
     * launcher — the launcher is a foreground activity, so no BAL
     * block — letting the MCP request the permission without sending
     * the user to Settings. {@link PermissionRequestActivity} stays in
     * the tree as the eventual target of that flow.
     */
    private String requestWriteContactsOrError() {
        if (hasWriteContacts()) return null;
        return needsPermissionError();
    }

    /**
     * Structured error the launcher parses to pop the "Open settings"
     * CTA. Includes the permission name and owning package so the
     * launcher can fire the right ACTION_APPLICATION_DETAILS_SETTINGS
     * intent without hardcoding ContactsMcp.
     */
    private static String needsPermissionError() {
        try {
            JSONObject e = new JSONObject();
            e.put("error", "needs_permission");
            e.put("permission", Manifest.permission.WRITE_CONTACTS);
            e.put("package", "com.android.contacts.mcp");
            return e.toString();
        } catch (JSONException je) {
            return "{\"error\":\"needs_permission\",\"permission\":\"android.permission.WRITE_CONTACTS\",\"package\":\"com.android.contacts.mcp\"}";
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String getFirstPhone(ContentResolver cr, long contactId) {
        try (Cursor cursor = cr.query(
                Phone.CONTENT_URI,
                new String[]{Phone.NUMBER},
                Phone.CONTACT_ID + " = ?",
                new String[]{String.valueOf(contactId)},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return null;
    }

    private String getFirstEmail(ContentResolver cr, long contactId) {
        try (Cursor cursor = cr.query(
                Email.CONTENT_URI,
                new String[]{Email.ADDRESS},
                Email.CONTACT_ID + " = ?",
                new String[]{String.valueOf(contactId)},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return null;
    }

    private static String error(String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("error", message);
            return err.toString();
        } catch (JSONException e) {
            return "{\"error\": \"unknown\"}";
        }
    }
}
