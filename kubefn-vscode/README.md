# KubeFn VS Code Extension

HeapExchange autocomplete, function snippets, and heap explorer for KubeFn projects.

## Install

1. Clone this repo and open the `kubefn-vscode` folder
2. Run `npm install` then `npm run compile`
3. Press `F5` to launch the Extension Development Host
4. Open a KubeFn project in the new window

Or install from VSIX:
```bash
cd kubefn-vscode
npm install && npm run compile
npx @vscode/vsce package
code --install-extension kubefn-0.1.0.vsix
```

## Features

### Snippets (works immediately, no build required)

Type the prefix in any Java file and press Tab:

| Prefix | Description |
|--------|-------------|
| `kubefn-handler` | Full KubeFn function with @FnRoute, @FnGroup, context |
| `kubefn-heap-read` | Read typed object from HeapExchange with orElseThrow |
| `kubefn-heap-read-fallback` | Read from HeapExchange with fallback default |
| `kubefn-heap-write` | Publish typed object to HeapExchange |
| `kubefn-test` | Function test with FakeHeapExchange and assertions |
| `kubefn-pipeline` | PipelineBuilder composition chain |
| `kubefn-schedule` | @FnSchedule annotation for cron functions |
| `kubefn-require` | HeapReader.require utility shorthand |
| `kubefn-publish` | HeapPublisher.publish utility shorthand |

### HeapKeys Autocomplete

When typing `HeapKeys.` in Java or Kotlin files, the extension suggests all known heap keys with their types and descriptions. It discovers keys by scanning `HeapKeys.java` in your workspace, falling back to the standard contract keys.

### Heap Explorer Sidebar

The activity bar shows a KubeFn icon. Click it to open two panels:

- **Heap Explorer** -- Live view of all objects on the HeapExchange. Each entry shows the key, type, and publisher. Expand an entry to see the full JSON value. Click the refresh button to fetch the latest state.

- **Functions** -- All deployed functions grouped by @FnGroup. Each function shows its route and HTTP methods. Useful for discovering what is running in your organism.

### Status Bar

A status bar item shows the connection state of the KubeFn runtime. Click it to refresh.

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `kubefn.organismUrl` | `http://localhost:8081` | URL of the KubeFn runtime admin API |

## Commands

Open the Command Palette (`Cmd+Shift+P`) and type "KubeFn":

- **KubeFn: Refresh Heap** -- Refresh the Heap Explorer and Functions panels
- **KubeFn: Show Trace** -- Open a trace by ID in the browser
- **KubeFn: New Function** -- Scaffold a new KubeFn function file interactively
