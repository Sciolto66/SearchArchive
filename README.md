# ArchiveSearcher

ArchiveSearcher is a JavaFX desktop app with two search modes:

- Search for filenames inside Java archive files.
- Search text in Codex CLI and Claude Code JSONL session history.

## Install and Run

The most user-friendly build is the native installer produced by the GitHub Actions workflow:

Sign in to Github and download from:
https://github.com/Sciolto66/SearchArchive/actions

- macOS: `ArchiveSearcher.dmg`
- Windows: `ArchiveSearcher.exe`
- Linux: `ArchiveSearcher.deb`

The workflow runs on pushes to `master`, version tags matching `v*`, and manual workflow dispatch.

For development, run the app from Maven:

```sh
mvn javafx:run
```

## Developer Build

```sh
mvn clean package
```

The packaged JAR is written to `target/ArchiveSearcher.jar`.

Native installers are built in CI with `jpackage` using the OS-specific Maven profiles: `mac`, `windows`, and `linux`.

## Search Archive Filenames

Use this mode when you want to find whether an archive contains a file whose name matches your search text.

1. Select `Archive filename` in `Search Mode`.
2. Enter a filename or partial filename in `Filename to Search`.
3. Click `Browse...` and select a supported archive.
4. Click `Start Search`, or press Enter while the search field is focused.
5. Double-click a result to view the archive path and matched entry.

Supported archive types:

- `.zip`
- `.jar`
- `.ear`
- `.sar`

Nested archives are searched as part of the archive path.

## Search JSONL History

Use this mode to search local Codex CLI or Claude Code conversation history files.

1. Select `JSONL history` in `Search Mode`.
2. Enter the text to search for in `Text to Search`.
3. Click `Browse...` and select the history folder.
4. Click `Start Search`, or press Enter while the search field is focused.
5. Double-click a result to open the chat-style view for the full JSONL session.

The search is case-insensitive and scans all `.jsonl` files under the selected folder, including subfolders. Results are shown as one row per matching, non-filtered JSONL line. The app remembers the last selected JSONL folder and last selected search mode.

### Result Table

JSONL history results show:

- `Title`: derived from the first meaningful user prompt in the session.
- `File`: the JSONL filename.
- `Line`: the matching line number, or merged line numbers when duplicate visible content is collapsed.

Double-click opens the normalized chat view. In that view, user and assistant messages can be expanded or collapsed, text can be selected for copying, and the matching turn is highlighted. The `Raw JSON` button opens the matching JSON line as pretty-printed JSON.

### Default History Locations

Select the root folder shown below. The app searches all subfolders from there.

| Tool | macOS | Linux | Windows |
| --- | --- | --- | --- |
| Codex CLI | `~/.codex/sessions` | `~/.codex/sessions` | `%USERPROFILE%\.codex\sessions` |
| Claude Code | `~/.claude/projects` | `~/.claude/projects` | `%USERPROFILE%\.claude\projects` |

Codex session files are usually below dated subfolders such as `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`. Claude Code session files are usually stored as JSONL transcripts below `~/.claude/projects`, grouped by project.

## Notes

The JSONL chat view filters internal tool calls, command output, telemetry, token count records, empty reasoning records, and other low-value system records so the transcript is easier to read. The raw JSON view remains available for inspecting the original matching line.

Both Codex and Claude histories are local files and may contain sensitive data that appeared in prompts, tool output, command output, or file contents.
