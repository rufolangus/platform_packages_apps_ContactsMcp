# ContactsMcp

First reference MCP-providing app for [AAOSP](https://github.com/rufolangus/AAOSP).
Paired with [CalendarMcp](./CalendarMcp_README.md) as of v0.5 to demo
cross-MCP chaining.

Declares five tools in its `AndroidManifest.xml` that the system LLM
(`LlmManagerService`) can invoke over Binder via `IMcpToolProvider`.

## Tools

**Read tools** (no consent prompt):

- `search_contacts` — search by name, phone, or email
- `get_contact` — full details for a contact by name
- `list_favorites` — starred contacts

**Write tools** (v0.5 — declared with
`android:mcpRequiresConfirmation="true"`, so every call pops a 4-button
HITL prompt in the launcher: *Once / This chat / Always / Deny*):

- `add_contact` — create a new contact from name + optional phone/email
- `update_contact` — append a phone or email to an existing contact
  matched by name

## Runtime permission layering (v0.5)

The app declares `WRITE_CONTACTS` as a `uses-permission` but it is
**intentionally not** granted via
`default-permissions-aaosp.xml`. This exposes the two-layer consent
model end-to-end:

1. **HITL tool consent** (LLM → tool) — the launcher's
   `ConsentPromptCard` gates the call the model *wants* to make.
2. **Android runtime permission** (tool → ContentProvider) — the MCP
   service itself must hold `WRITE_CONTACTS` to touch
   `ContactsContract`.

If the runtime permission is missing, `ContactsMcpService.addContact` /
`updateContact` launches a translucent `PermissionRequestActivity` to
host `requestPermissions` in-app. On Android 15 background-activity-launch
gating this can be blocked, in which case the service returns
`{"error":"needs_permission","permission":"android.permission.WRITE_CONTACTS",
"package":"com.android.contacts.mcp"}` and the launcher's
`PermissionRequiredCard` offers a one-tap *Open settings* fallback to the
app's details page.

Account binding: writes use `accountType=null` / `accountName=null` so
the contact lives in the "local device" account — works out of the box on
stock Cuttlefish with no Google account signed in.

---

This is the canonical example of how any Android app becomes an MCP server
by declaring an `<mcp-server>` block in its manifest. See the
[AAOSP umbrella](https://github.com/rufolangus/AAOSP) for the full project,
[architecture doc](https://github.com/rufolangus/AAOSP/blob/main/docs/AAOSP_ARCHITECTURE.md)
for the technical detail, and the
[v0.3 demo video](https://www.loom.com/share/edac9d03682b4413afd2fcc80693275e)
(predates the v0.5 HITL consent flow + CalendarMcp — still useful for
the base `search_contacts` path).

## License

Apache 2.0.
