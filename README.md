# ContactsMcp

Reference MCP-providing app for [AAOSP](https://github.com/rufolangus/AAOSP).

Declares three tools in its `AndroidManifest.xml` that the system LLM
(`LlmManagerService`) can invoke over Binder via `IMcpToolProvider`:

- `search_contacts` — search by name, phone, or email
- `get_contact` — get full details for a contact by name
- `list_favorites` — list starred contacts

This is the canonical example of how any Android app becomes an MCP server
by declaring an `<mcp-server>` block in its manifest. See the
[AAOSP umbrella](https://github.com/rufolangus/AAOSP) for the full project,
[architecture doc](https://github.com/rufolangus/AAOSP/blob/main/docs/AAOSP_ARCHITECTURE.md)
for the technical detail, and the
[live demo](https://www.loom.com/share/edac9d03682b4413afd2fcc80693275e).

## License

Apache 2.0.
