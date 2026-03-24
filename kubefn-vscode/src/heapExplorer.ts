import * as vscode from 'vscode';

interface HeapEntry {
    key: string;
    type: string;
    publisher?: string;
    value?: unknown;
}

export class HeapExplorerProvider implements vscode.TreeDataProvider<HeapItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<HeapItem | undefined | void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    private entries: HeapEntry[] = [];

    constructor(private readonly organismUrl: string) {}

    refresh(): void {
        this.entries = [];
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: HeapItem): vscode.TreeItem {
        return element;
    }

    async getChildren(element?: HeapItem): Promise<HeapItem[]> {
        if (element) {
            // Expand a heap entry to show its value
            if (element.entry?.value !== undefined) {
                const valueStr = JSON.stringify(element.entry.value, null, 2);
                const lines = valueStr.split('\n');
                return lines.map(line => {
                    const item = new HeapItem(line, vscode.TreeItemCollapsibleState.None);
                    item.contextValue = 'heapValue';
                    return item;
                });
            }
            return [];
        }

        // Root level: fetch heap entries
        await this.fetchHeap();

        if (this.entries.length === 0) {
            const empty = new HeapItem(
                'No heap entries (is the runtime running?)',
                vscode.TreeItemCollapsibleState.None
            );
            empty.iconPath = new vscode.ThemeIcon('info');
            return [empty];
        }

        return this.entries.map(entry => {
            const label = entry.key;
            const hasValue = entry.value !== undefined;
            const state = hasValue
                ? vscode.TreeItemCollapsibleState.Collapsed
                : vscode.TreeItemCollapsibleState.None;

            const item = new HeapItem(label, state);
            item.entry = entry;
            item.description = entry.type;
            item.tooltip = `Key: ${entry.key}\nType: ${entry.type}${entry.publisher ? `\nPublisher: ${entry.publisher}` : ''}`;
            item.iconPath = new vscode.ThemeIcon('symbol-variable');
            item.contextValue = 'heapEntry';
            return item;
        });
    }

    private async fetchHeap(): Promise<void> {
        try {
            const response = await fetch(`${this.organismUrl}/admin/heap`);
            if (response.ok) {
                const data = await response.json() as HeapEntry[];
                this.entries = Array.isArray(data) ? data : [];
            }
        } catch {
            this.entries = [];
        }
    }
}

class HeapItem extends vscode.TreeItem {
    entry?: HeapEntry;

    constructor(
        public readonly label: string,
        public readonly collapsibleState: vscode.TreeItemCollapsibleState
    ) {
        super(label, collapsibleState);
    }
}
