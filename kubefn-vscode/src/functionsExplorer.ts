import * as vscode from 'vscode';

interface FunctionInfo {
    name: string;
    group: string;
    route: string;
    methods: string[];
    className?: string;
}

interface GroupNode {
    kind: 'group';
    group: string;
    functions: FunctionInfo[];
}

interface FunctionNode {
    kind: 'function';
    fn: FunctionInfo;
}

type TreeNode = GroupNode | FunctionNode;

export class FunctionsExplorerProvider implements vscode.TreeDataProvider<TreeNode> {
    private _onDidChangeTreeData = new vscode.EventEmitter<TreeNode | undefined | void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    private functions: FunctionInfo[] = [];

    constructor(private readonly organismUrl: string) {}

    refresh(): void {
        this.functions = [];
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: TreeNode): vscode.TreeItem {
        if (element.kind === 'group') {
            const item = new vscode.TreeItem(
                element.group,
                vscode.TreeItemCollapsibleState.Expanded
            );
            item.iconPath = new vscode.ThemeIcon('symbol-namespace');
            item.contextValue = 'fnGroup';
            item.description = `${element.functions.length} function${element.functions.length === 1 ? '' : 's'}`;
            return item;
        }

        const fn = element.fn;
        const item = new vscode.TreeItem(
            fn.name,
            vscode.TreeItemCollapsibleState.None
        );
        item.description = `${fn.methods.join(', ')} ${fn.route}`;
        item.tooltip = `${fn.name}\nGroup: ${fn.group}\nRoute: ${fn.route}\nMethods: ${fn.methods.join(', ')}`;
        item.iconPath = new vscode.ThemeIcon('symbol-function');
        item.contextValue = 'fnEntry';

        // Try to open the source file on click
        if (fn.className) {
            item.command = {
                command: 'kubefn.openFunctionSource',
                title: 'Open Source',
                arguments: [fn.className]
            };
        }

        return item;
    }

    async getChildren(element?: TreeNode): Promise<TreeNode[]> {
        if (element) {
            if (element.kind === 'group') {
                return element.functions.map(fn => ({
                    kind: 'function' as const,
                    fn
                }));
            }
            return [];
        }

        // Root: fetch and group functions
        await this.fetchFunctions();

        if (this.functions.length === 0) {
            return [];
        }

        const groups = new Map<string, FunctionInfo[]>();
        for (const fn of this.functions) {
            const list = groups.get(fn.group) || [];
            list.push(fn);
            groups.set(fn.group, list);
        }

        return Array.from(groups.entries()).map(([group, functions]) => ({
            kind: 'group' as const,
            group,
            functions
        }));
    }

    private async fetchFunctions(): Promise<void> {
        try {
            const response = await fetch(`${this.organismUrl}/admin/functions`);
            if (response.ok) {
                const data = await response.json() as FunctionInfo[];
                this.functions = Array.isArray(data) ? data : [];
            }
        } catch {
            this.functions = [];
        }
    }
}
